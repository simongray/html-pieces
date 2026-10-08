(ns dk.simongray.html-pieces
  "HTML embedded in other content, such as show notes, comments or the
  fields of a CMS, read into Hiccup and rendered from there.

  A parse gives a sequence of nodes. An element is a vector of a keyword
  tag, a map of keyword attributes and its children, and text is a string.
  For HTML that a client can render, use the hiccup function. It parses
  and sanitizes HTML, and makes paragraphs of plain text. The text and
  markdown functions render HTML or Hiccup as text, and emit writes Hiccup
  back as HTML.

  The tokens are those of the HTML Standard, by
  dk.simongray.html-pieces.tokenizer, and the tree follows the rules of
  the standard that such content needs.

  TODO: the rules of the tree builder that a browser follows and this
  leaves out, by an option or a namespace of their own: formatting that a
  browser carries on past a misnested end tag, the repairs of tables, and
  SVG and MathML.

  TODO: an optional report of what parse discarded and what sanitize
  removed, e.g. in metadata, so that a validator can tell the author of
  the HTML what a client won't show."
  (:require [clojure.string :as str]
            [dk.simongray.html-pieces.entities :as entities]
            [dk.simongray.html-pieces.tokenizer :as tokenizer]))

;; HTML Standard, "ASCII whitespace": tab, LF, FF, CR and space. HTML
;; collapses and trims no other whitespace, so a no-break space stays. The
;; \s of regular expressions and str/trim differ on it between the JVM and
;; JavaScript, so neither is used for text.

(defn ^:no-doc whitespace?
  "Whether the character `c` is ASCII whitespace."
  [c]
  (case c
    (\tab \newline \formfeed \return \space) true
    false))

(defn ^:no-doc trim-whitespace
  "The text `s` without the ASCII whitespace at its ends."
  [s]
  (let [n     (count s)
        end   (loop [i n]
                (if (and (pos? i) (whitespace? (tokenizer/char-at s n (dec i))))
                  (recur (dec i))
                  i))
        start (loop [i 0]
                (if (and (< i end) (whitespace? (tokenizer/char-at s n i)))
                  (recur (inc i))
                  i))]
    (if (and (zero? start) (= n end))
      s
      (subs s start end))))

(defn ^:no-doc blank?
  "Whether the text `s` holds nothing but ASCII whitespace."
  [s]
  (let [n (count s)]
    (loop [i 0]
      (cond
        (= n i)                                   true
        (whitespace? (tokenizer/char-at s n i))   (recur (inc i))
        :else                                     false))))

(def ^:no-doc markup
  "A comment or a whole tag, which markup? looks for."
  (re-pattern (str "<!--"
                   "|<[a-zA-Z][a-zA-Z0-9]*(?:[\\t\\n\\f\\r ][^<>]*)?/?>"
                   "|</[a-zA-Z][a-zA-Z0-9]*[\\t\\n\\f\\r ]*>")))

(defn markup?
  "Whether the text `s` is HTML rather than plain text: whether it holds a
  comment or a whole tag. Plain text may hold an email address in angle
  brackets, or a less-than sign before a 3, and neither is a tag."
  [s]
  (boolean (re-find markup (str s))))

;; HTML Standard, "Elements": the void elements, and param of older pages
(def ^:no-doc void-elements
  "Elements that never have content or an end tag."
  #{:area :base :br :col :embed :hr :img :input :link :meta :param :source
    :track :wbr})

;; HTML Standard, "Optional tags", as of 2026-09-29
(def ^:no-doc block-elements
  "Elements whose start tag closes an open paragraph, as HTML defines."
  #{:address :article :aside :blockquote :details :dialog :div :dl :fieldset
    :figcaption :figure :footer :form :h1 :h2 :h3 :h4 :h5 :h6 :header :hgroup
    :hr :main :menu :nav :ol :p :pre :search :section :table :ul})

;; HTML Standard, "Optional tags", and 13.2.6.4.7 for a, which closes an
;; open a up to the markers of the list of active formatting elements
(def ^:no-doc implied-end
  "For each tag, the open elements that its start tag closes, and the
  ancestors where the search for them stops. A new li closes an open li,
  but only inside the same list."
  {:li     [#{:li} #{:ul :ol :menu}]
   :dt     [#{:dt :dd} #{:dl}]
   :dd     [#{:dt :dd} #{:dl}]
   :tr     [#{:tr :td :th} #{:table}]
   :td     [#{:td :th} #{:tr :table}]
   :th     [#{:td :th} #{:tr :table}]
   :option [#{:option} #{:select}]
   :a      [#{:a} #{:td :th :caption :applet :marquee :object :template}]})

(def ^:no-doc headings
  "The headings, whose start tag closes a heading that's the current node,
  as 13.2.6.4.7 of the HTML Standard has it."
  #{:h1 :h2 :h3 :h4 :h5 :h6})

(def ^:no-doc foreign-roots
  "The roots of SVG and MathML, inside which an element can close itself
  with a slash, as 13.2.6.5 of the HTML Standard has it."
  #{:svg :math})

(def ^:no-doc paragraph-scope
  "The elements that bound the search for an open p: block-level ones, list
  items and table cells."
  (into block-elements #{:li :td :th :dt :dd :body}))

(def ^:no-doc newline-dropping
  "The elements whose start tag drops a line feed right after it, as
  13.2.6.4.7 of the HTML Standard has it."
  #{:pre :listing :textarea})

(def max-depth
  "How deep the parse function nests elements, far deeper than real show
  notes go. An element opened deeper is kept empty, and its content goes
  to its parent, so that no walk of the tree overflows the stack."
  128)

;; The parse builds the tree in a builder, a transient map of :stack, the
;; open elements, the root first, each a transient vector [tag attrs &
;; children], :open, how many are open by tag, so that an end tag nothing
;; matches costs nothing to ignore, and :max-depth. Each function of the
;; builder takes it and gives it back, through the values that assoc!,
;; conj! and pop! return. Tags are the keywords of Hiccup.

(defn ^:no-doc merge-text
  "The element `node` with each run of adjacent strings among its children
  joined into one. Only a comment or a stray end tag between two texts
  leaves such a run, so most elements come back as they are."
  [node]
  (let [text? #(string? (nth node %))
        runs? (loop [i 3]
                (cond
                  (<= (count node) i)             false
                  (and (text? i) (text? (dec i))) true
                  :else                           (recur (inc i))))]
    (if runs?
      (into []
            (comp (partition-by string?)
                  (mapcat #(if (string? (first %)) [(apply str %)] %)))
            node)
      node)))

(defn- top
  "The stack index of the top element of the builder `b`."
  [b]
  (dec (count (:stack b))))

(defn- tag-at
  "The tag of the open element at the stack index `i` of the builder `b`."
  [b i]
  (nth (nth (:stack b) i) 0))

(defn- open?
  "Whether an element of one of `tags` is open in the builder `b`."
  [b tags]
  (let [open (:open b)]
    (some #(pos? (get open % 0)) tags)))

(defn- update-count!
  "The builder `b` with the count of the open elements of `tag` changed by
  `f`."
  [b tag f]
  (let [open (:open b)]
    (assoc! b :open (assoc! open tag (f (get open tag 0))))))

(defn- append!
  "The builder `b` with `child` appended to its top element."
  [b child]
  (let [stack (:stack b)
        i     (top b)]
    (assoc! b :stack (assoc! stack i (conj! (nth stack i) child)))))

(defn- push!
  "The builder `b` with an element of `tag` and `attrs` opened on top."
  [b tag attrs]
  (-> b
      (assoc! :stack (conj! (:stack b) (transient [tag attrs])))
      (update-count! tag inc)))

(defn- close-top!
  "The builder `b` with its top element closed into its parent. The text of
  the element is joined here, once, rather than on every token."
  [b]
  (let [stack (:stack b)
        node  (persistent! (nth stack (top b)))]
    (-> b
        (assoc! :stack (pop! stack))
        (update-count! (nth node 0) dec)
        (append! (merge-text node)))))

(defn ^:no-doc open-index
  "The stack index of the nearest open element of the builder `b` whose
  tag is in `tags`, or nil. The search goes down from the top and gives up
  at one of `boundaries`."
  [b tags boundaries]
  (when (open? b tags)
    (loop [i (top b)]
      (when (pos? i)
        (let [tag (tag-at b i)]
          (cond
            (tags tag)       i
            (boundaries tag) nil
            :else            (recur (dec i))))))))

(defn- close-through!
  "The builder `b` with the open elements closed from the top down to the
  nearest one whose tag is in `tags`, that one included. Nothing closes
  when one of `boundaries` comes first or none of `tags` is open."
  [b tags boundaries]
  (if-let [i (open-index b tags boundaries)]
    (loop [b b k (- (count (:stack b)) i)]
      (if (zero? k)
        b
        (recur (close-top! b) (dec k))))
    b))

(defn- open-element!
  "The builder `b` with the element `tag` and its `attrs` opened, or
  appended when it's void, `self-closing?` in SVG or MathML, or past the
  :max-depth of the builder. The elements that its start tag implies an
  end for are closed first."
  [b tag attrs self-closing?]
  (let [b (if (and (block-elements tag) (open-index b #{:p} paragraph-scope))
            (close-through! b #{:p} #{})
            b)
        b (if-let [[tags boundaries] (implied-end tag)]
            (close-through! b tags boundaries)
            b)
        b (if (and (headings tag) (headings (tag-at b (top b))))
            (close-top! b)
            b)]
    (if (or (void-elements tag)
            (and self-closing? (or (foreign-roots tag) (open? b foreign-roots)))
            (> (count (:stack b)) (:max-depth b)))
      (append! b [tag attrs])
      (push! b tag attrs))))

(def ^:no-doc nul-text
  "U+0000 NULL as a text, which the tree builder drops from text."
  (str tokenizer/nul))

(defn- step!
  "The builder `b` after the `token` of dk.simongray.html-pieces.tokenizer, as a
  few of the rules of 13.2.6.4.7 of the HTML Standard build the tree."
  [b token]
  (let [drop-newline? (:drop-newline? b)
        b             (cond-> b
                        drop-newline? (dissoc! :drop-newline?))]
    (cond
      (string? token)
      (let [text (cond-> token
                   (str/includes? token nul-text) (str/replace nul-text ""))
            text (cond-> text
                   (and drop-newline? (str/starts-with? text "\n")) (subs 1))]
        (cond-> b
          (seq text) (append! text)))

      (= :start-tag (:type token))
      (let [{:keys [name attrs self-closing?]} token
            tag                                (keyword name)]
        (cond-> (open-element! b tag (update-keys attrs keyword) self-closing?)
          (newline-dropping tag) (assoc! :drop-newline? true)))

      ;; an end tag br is a line break
      (= :end-tag (:type token))
      (let [tag (keyword (:name token))]
        (if (= :br tag)
          (open-element! b :br {} false)
          (close-through! b #{tag} #{})))

      :else
      b)))

(defn parse
  "The HTML fragment `s` as a sequence of Hiccup nodes, nested at most as
  deep as the :max-depth of `opts`, or max-depth.

  The tokens are those of the HTML Standard, by
  dk.simongray.html-pieces.tokenizer, and the tree follows the rules of
  the standard that embedded HTML needs:

  - unclosed paragraphs, list items, table cells, headings and links
    close where HTML says they do
  - void elements, and the elements of SVG and MathML that close
    themselves with a slash, have no content
  - stray end tags are ignored, and </br> is a line break
  - comments, DOCTYPEs and processing instructions vanish

  Misnested inline elements close through the nearest match, where a
  browser would carry them on. Tags and attribute keys are lower-case
  keywords, an attribute without a value has the empty string, and text
  is a string with its character references decoded.

  A whole page parses too, e.g. for its link, meta and title elements, but
  its html, head and body elements stay as they're written, without the
  rules that a browser has for them.

  Hiccup renderers take a sequence as a fragment, so the result can go
  straight into a parent element."
  ([s]
   (parse s {}))
  ([s opts]
   (let [builder (transient {:stack     (transient [(transient [:root {}])])
                             :open      (transient {})
                             :max-depth (or (:max-depth opts) max-depth)})]
     (loop [b (reduce step! builder (tokenizer/tokens s))]
       (if (= 1 (count (:stack b)))
         (apply list (drop 2 (merge-text (persistent! (nth (:stack b) 0)))))
         (recur (close-top! b)))))))

(defn ^:no-doc scheme
  "The scheme of the URL `s` in lower case, or nil for a relative URL.

  The URL is read as a browser reads it, so a tab inside javascript:
  doesn't hide the scheme. The tabs and line breaks anywhere in it are
  dropped, and so are the control characters and spaces in front."
  [s]
  (let [s (-> (str s)
              (str/replace #"[\t\n\r]" "")
              (str/replace #"^[\x00-\x20]+" ""))]
    (some-> (re-find #"^[a-zA-Z][a-zA-Z0-9+.-]*(?=:)" s) str/lower-case)))

(def allowed-tags
  "The elements that sanitize keeps."
  #{:p :br :a :strong :b :em :i :u :s :strike :del :ins :mark :small :sup :sub
    :ul :ol :li :dl :dt :dd :h1 :h2 :h3 :h4 :h5 :h6 :blockquote :q :cite :abbr
    :dfn :code :kbd :pre :hr :img :span :div :figure :figcaption
    :table :thead :tbody :tfoot :tr :td :th :caption})

(def dropped-tags
  "The elements that sanitize removes with their content."
  #{:script :style :iframe :object :embed :form :input :button :select :option
    :textarea :noscript :svg :math :template :head :title :meta :link :base
    :frame :frameset :applet :audio :video :canvas})

(def allowed-attributes
  "The attributes that sanitize keeps, by element."
  {:a          #{:href :title :rel :hreflang}
   :img        #{:src :alt :title :width :height}
   :td         #{:colspan :rowspan}
   :th         #{:colspan :rowspan :scope}
   :ol         #{:start :reversed}
   :blockquote #{:cite}
   :q          #{:cite}
   :abbr       #{:title}
   :dfn        #{:title}})

;; HTML Standard, "Index", the attributes whose value is a URL, with
;; background and longdesc of older pages and xlink:href of SVG
(def url-attributes
  "The attributes whose value is a URL, which sanitize checks."
  #{:action :background :cite :data :formaction :href :itemid :longdesc
    :manifest :poster :src :xlink:href})

(def url-list-attributes
  "The attributes whose value is a list of URLs, which sanitize checks one
  by one: ping and itemtype, separated by spaces, and srcset, separated by
  commas, with a descriptor after each URL."
  #{:itemtype :ping :srcset})

(def allowed-schemes
  "The schemes of the URLs that sanitize keeps, and that text and markdown
  show as links."
  #{"http" "https" "mailto"})

(def paragraph-tags
  "Elements set off by a blank line in text and Markdown."
  #{:p :h1 :h2 :h3 :h4 :h5 :h6 :blockquote :pre :ul :ol :dl :table :hr :figure})

(def line-tags
  "Elements that end a line in text and Markdown."
  #{:div :li :tr :dt :dd :figcaption :address :caption :thead :tbody :tfoot
    :section :article})

(def max-quotes
  "How deeply markdown nests quotes. A quote deeper inside is a paragraph,
  so that no line carries a > for each of hundreds of levels."
  16)

(def default-options
  "The default of each option. Every function takes one map of options and
  reads the keys that it knows:

  - :max-depth, how deep parse nests elements
  - :allowed-tags, :dropped-tags and :allowed-attributes, the tables of
    sanitize
  - :allowed-schemes, the schemes of the URLs that sanitize keeps, and
    :url-attributes and :url-list-attributes, the attributes that hold them
  - :url, a function of a URL that gives the URL to keep, or nil
  - :paragraph-tags and :line-tags, where text and markdown break lines
  - :max-quotes, how deeply markdown nests quotes
  - :links?, true for text to put the URL after each link
  - :quirks?, false to leave out a repair that the HTML Standard doesn't
    make: reading a C1 control in text as windows-1252

  Each of them but :url, :links? and :quirks? is a var of the same name."
  {:max-depth           max-depth
   :allowed-tags        allowed-tags
   :dropped-tags        dropped-tags
   :allowed-attributes  allowed-attributes
   :allowed-schemes     allowed-schemes
   :url-attributes      url-attributes
   :url-list-attributes url-list-attributes
   :url                 identity
   :paragraph-tags      paragraph-tags
   :line-tags           line-tags
   :max-quotes          max-quotes
   :links?              false
   :quirks?             true})

(defn- options
  "The `opts` with the default-options of the keys that they leave out or
  give as nil."
  [opts]
  (if (empty? opts)
    default-options
    (into default-options (remove (comp nil? val)) opts)))

(def ^:no-doc structural-tags
  "Elements whose text children are layout whitespace, never content."
  #{:ul :ol :dl :table :thead :tbody :tfoot :tr})

(defn- safe-url?
  "Whether the URL `s` is absolute, with a scheme of `schemes`, read as a
  browser reads it."
  [schemes s]
  (contains? schemes (scheme s)))

(defn- element?
  "Whether `x` is a Hiccup element: a vector with a keyword tag."
  [x]
  (and (vector? x) (keyword? (nth x 0 nil))))

(defn ^:no-doc parts
  "The tag, the attributes and the children of the Hiccup element `node`,
  whose attribute map can be left out, e.g. [:p \"x\"]."
  [node]
  (if (map? (get node 1))
    [(node 0) (node 1) (subvec node 2)]
    [(node 0) {} (subvec node 1)]))

(defn- checked-url
  "The URL `s` as the :url of `opts` gives it, when that's a URL with one
  of their :allowed-schemes, or else nil."
  [opts s]
  (when (string? s)
    (let [u ((:url opts) s)]
      (when (and (string? u) (safe-url? (:allowed-schemes opts) u))
        u))))

(defn- checked-urls
  "The list of URLs `s` of the attribute `k`, each one as checked-url gives
  it with `opts`, or nil when one isn't allowed."
  [opts k s]
  (when (string? s)
    (let [srcset?   (= :srcset k)
          items     (->> (str/split s (if srcset? #"," #"[\t\n\f\r ]+"))
                         (map trim-whitespace)
                         (remove blank?))
          candidate #"([^\t\n\f\r ]+)(.*)"
          checked   (for [item items
                          :let [[_ u more] (re-matches candidate item)]]
                      (some-> (checked-url opts u) (str more)))]
      (when (and (seq checked) (every? some? checked))
        (str/join (if srcset? ", " " ") checked)))))

(defn- safe-attributes
  "The attributes `attrs` of an element `tag` that the :allowed-attributes
  of `opts` has, with each URL as checked-url gives it, and without one
  that isn't allowed."
  [opts tag attrs]
  (let [{:keys [allowed-attributes url-attributes url-list-attributes]} opts]
    (into {} (for [[k v] attrs
                   :when (contains? (get allowed-attributes tag) k)
                   :let  [v (cond
                              (url-attributes k)      (checked-url opts v)
                              (url-list-attributes k) (checked-urls opts k v)
                              :else                   v)]
                   :when (some? v)]
               [k v]))))

(defn sanitize
  "The Hiccup `nodes` that a client may render, by the rules below and the
  `opts` of default-options. In the result:

  - the elements and attributes of :allowed-tags and :allowed-attributes
    are kept
  - a URL is kept when its scheme is one of :allowed-schemes, after the
    function :url has rewritten it
  - the elements of :dropped-tags are removed with their content
  - any other element is replaced by its children
  - lists and tables lose the whitespace of their layout

  The function :url can e.g. make a relative URL absolute against the page
  that the HTML came from. A client would resolve it against its own page,
  so without such a function a relative URL is left out.

  Text in the result is decoded, e.g. &lt; is <, so render it with a
  renderer that escapes text, such as Replicant, Reagent,
  hiccup2.core/html or emit."
  ([nodes]
   (sanitize nodes {}))
  ([nodes opts]
   (let [opts    (options opts)
         allowed (:allowed-tags opts)
         dropped (:dropped-tags opts)
         layout? #(and (string? %) (blank? %))]
     (letfn [(node [x]
               (cond
                 (string? x)        [x]
                 (not (element? x)) []
                 :else
                 (let [[tag attrs children] (parts x)]
                   (if (dropped tag)
                     []
                     (let [kids (into []
                                      (comp (mapcat node)
                                            (if (structural-tags tag)
                                              (remove layout?)
                                              identity))
                                      children)
                           safe (safe-attributes opts tag attrs)]
                       (if (allowed tag)
                         [(into [tag safe] kids)]
                         kids))))))]
       (let [nodes (if (element? nodes) [nodes] nodes)]
         (apply list (into [] (mapcat node) nodes)))))))

(defn- decoded
  "The text `s` with its character references decoded and every other
  character kept, as HTML reads the text of a title: the RCDATA state of
  13.2.5.2 of the HTML Standard."
  [s]
  (apply str (tokenizer/tokens s {:state :rcdata})))

(defn- paragraphs
  "The plain text `s` as paragraphs, with blank lines between them and line
  breaks within."
  [s]
  (let [text (trim-whitespace (decoded s))]
    (apply list (for [paragraph (str/split text #"\n[\t\n\f\r ]*\n")
                      :let  [lines (->> (str/split-lines paragraph)
                                        (map trim-whitespace)
                                        (remove blank?))]
                      :when (seq lines)]
                  (into [:p {}] (interpose [:br {}] lines))))))

(defn hiccup
  "The Hiccup that a client can render for the HTML or plain text `s`, as a
  sequence of nodes to put inside a parent element. HTML is parsed and
  sanitized with the `opts` of parse and sanitize, and plain text becomes
  paragraphs with line breaks. Text in the result is decoded, so it needs
  a renderer that escapes text, as sanitize says."
  ([s]
   (hiccup s {}))
  ([s opts]
   (let [s (str s)]
     (if (markup? s)
       (sanitize (parse s opts) opts)
       (paragraphs s)))))

(defn- nodes-of
  "The nodes of `x`, which is a string, a node or nodes. A string is parsed
  with `opts` when it holds markup, or read as paragraphs when it's plain
  text, and anything else is no nodes."
  [x opts]
  (cond
    (string? x)     (if (markup? x) (parse x opts) (paragraphs x))
    (element? x)    [x]
    (sequential? x) x
    :else           []))

(defn- collapse
  "The text `s` with each run of ASCII whitespace as one space. Most text
  has only single spaces, which indexOf tells without a regular
  expression, and then it stays as it is."
  [s]
  (if (some #(str/includes? s %) ["\n" "  " "\t" "\r" "\f"])
    (str/replace s #"[\t\n\f\r ]+" " ")
    s))

(def ^:no-doc pre-mark
  "The mark that render puts at the start of each line of preformatted
  text, so that tidy leaves its indentation alone and then removes it.
  It's a character of the private use area, which no text holds."
  "\uE000")

(defn- marked-lines
  "The preformatted text `s` with pre-mark at the start of each line, and
  without the line breaks at its end."
  [s]
  (let [end (loop [i (count s)]
              (if (and (pos? i) (contains? #{\newline \return} (nth s (dec i))))
                (recur (dec i))
                i))]
    (str pre-mark (str/replace (subs s 0 end) "\n" (str "\n" pre-mark)))))

(defn- controls?
  "Whether the text `s` holds a control character but a tab or a line
  feed. Few texts hold one, and a loop tells it faster than a regular
  expression on the JVM."
  [s]
  #?(:clj  (let [^String s s
                 n         (.length s)]
             (loop [i 0]
               (if (< i n)
                 (let [c (int (.charAt s i))]
                   (if (or (and (< c 32) (not (== c 9)) (not (== c 10)))
                           (and (<= 127 c) (<= c 159)))
                     true
                     (recur (inc i))))
                 false)))
     :cljs (boolean (re-find #"[\x00-\x08\x0B-\x1F\x7F-\x9F]" s))))

;; CSS Text 3, "White Space Processing & Control Characters": a browser
;; shows a carriage return as a space, and other control characters but
;; tabs and line feeds as boxes, if at all
(defn- shown
  "The text `s` with each carriage return as a space, and without the
  other control characters but tabs and line feeds, which a terminal or a
  notification could obey. With `quirks?`, a C1 control, which is mostly
  a character of windows-1252 read as Latin-1, is that character where it
  has one, as for a numeric reference in 13.2.5.84 of the HTML Standard."
  [s quirks?]
  (if (controls? s)
    (cond-> (str/replace s "\r" " ")
      quirks?
      (str/replace #"[\x80-\x9F]"
                   #(if-let [x (get tokenizer/c1-replacements
                                    (tokenizer/code (first %)))]
                      (entities/code-point->string x)
                      ""))

      :always
      (str/replace #"[\x00-\x08\x0B\x0C\x0E-\x1F\x7F-\x9F]" ""))
    s))

(defn- tidy
  "The rendered `s` as shown gives it with `quirks?`, trimmed, with each
  run of blank lines cut to one. Its lines are trimmed too, except those
  of preformatted text."
  [s quirks?]
  (let [trim-line #(if (str/starts-with? % pre-mark) % (trim-whitespace %))
        lines     (->> (str/split-lines (shown s quirks?))
                       (into [] (map trim-line))
                       (str/join "\n"))
        text      (trim-whitespace (cond-> lines
                                     (str/includes? lines "\n\n\n")
                                     (str/replace #"\n{3,}" "\n\n")))]
    (cond-> text
      (str/includes? text pre-mark) (str/replace pre-mark ""))))

(defn- render
  "The `nodes` rendered as text by the functions `inline` and `element`,
  starting from `context`.

  The `inline` function renders a string, and takes the string and the
  context. The `element` function renders an element, and takes its tag
  and attributes, its rendered children and the context. The context is
  the options of default-options, with these keys too:

  - :list is :ul or :ol inside a list
  - :index is a volatile that counts the list items
  - :pre, :code and :quotes count the pre, code and blockquote elements
    that it's in, the element itself included

  The elements of :dropped-tags render as nothing, as a browser shows no
  script. Every line of the outermost pre starts with pre-mark."
  [nodes inline element context]
  (letfn [(node [x outer]
            (cond
              (string? x)        (inline x outer)
              (not (element? x)) ""
              :else
              (let [[tag attrs children] (parts x)
                    counted              #(update %1 %2 (fnil inc 0))]
                (if ((:dropped-tags outer) tag)
                  ""
                  (let [ctx   (cond-> outer
                                (#{:ul :ol} tag)
                                (assoc :list tag :index (volatile! 0))

                                (= :pre tag)        (counted :pre)
                                (= :code tag)       (counted :code)
                                (= :blockquote tag) (counted :quotes))
                        inner (str/join (into [] (map #(node % ctx)) children))]
                    (element [tag attrs]
                             (if (and (= :pre tag) (not (:pre outer)))
                               (marked-lines inner)
                               inner)
                             ctx))))))]
    (tidy (str/join (into [] (map #(node % context)) nodes))
          (:quirks? context))))

(defn- list-marker
  [{:keys [list index]}]
  (case list
    :ol (str (vswap! index inc) ". ")
    :ul "- "
    ""))

(defn- text-element
  "The text of the element of `tag` and `attrs`, whose children render as
  `inner`, in the context `ctx` of render, with the URL of a link when the
  context has :links?."
  [[tag attrs] inner ctx]
  (let [href   (when (:links? ctx)
                 (some-> (:href attrs) str (str/replace #"[\t\n\r]" "")))
        shown? (and href
                    (safe-url? (:allowed-schemes ctx) href)
                    (not= (trim-whitespace inner) href))]
    (cond
      (= :br tag)                  "\n"
      (= :hr tag)                  "\n\n"
      (= :img tag)                 (collapse (str (:alt attrs)))
      (= :a tag)                   (if shown? (str inner " (" href ")") inner)
      (= :li tag)                  (str (list-marker ctx)
                                        (trim-whitespace inner) "\n")
      (< 1 (:pre ctx 0))           inner
      ((:paragraph-tags ctx) tag)  (str "\n\n" inner "\n\n")
      ((:line-tags ctx) tag)       (str inner "\n")
      :else                        inner)))

(defn text
  "The HTML text or Hiccup `x` as plain text, by the `opts` of
  default-options. In the text:

  - paragraphs and the other elements of :paragraph-tags are separated by
    a blank line, and those of :line-tags end a line
  - list items are on their own lines, with a marker
  - a link is its text, followed by its URL in parentheses when :links?
    and the scheme of the URL is one of :allowed-schemes
  - an image is its alt text
  - a carriage return is a space, and other control characters are left
    out, but for tabs and line breaks. When :quirks?, a C1 control is the
    character of windows-1252 that it stands for."
  ([x]
   (text x {}))
  ([x opts]
   (let [opts (options opts)]
     (render (nodes-of x opts)
             (fn [s ctx] (if (:pre ctx) s (collapse s)))
             text-element
             opts))))

;; CommonMark 0.31.2, section 2.4: a backslash escapes any ASCII
;; punctuation. These start inline markup, with the | of tables and the ~
;; of strikethrough in GitHub Flavored Markdown.
(def ^:no-doc markdown-escapes
  "The characters that markdown escapes in text, by character."
  {\\ "\\\\" \` "\\`" \* "\\*" \_ "\\_" \[ "\\[" \] "\\]" \< "\\<" \> "\\>"
   \| "\\|" \~ "\\~"})

;; CommonMark 0.31.2, section 2.5
(def ^:no-doc reference-start
  "An ampersand that Markdown could read as the start of a character
  reference."
  #"&(?=#?[0-9A-Za-z]{1,32};)")

;; CommonMark 0.31.2, sections 4.1 to 4.3 and 5.2
(def ^:no-doc block-start
  "What starts a heading, a thematic break or a list at the start of a
  line, after the space in front: a # + = or -, or the digits of an
  ordered list and the . or ) after them."
  #"^([\t\n\f\r ]*)(?:([#+=-])|([0-9]{1,9})([.)]))")

(defn- markdown-inline
  "The text `s` with the characters of markdown-escapes and the start of a
  character reference escaped, so that Markdown shows it as it is."
  [s]
  (cond-> (str/escape s markdown-escapes)
    (str/includes? s "&") (str/replace reference-start (constantly "\\&"))))

(defn- markdown-text
  "The text `s` as markdown-inline escapes it, and with what would start a
  block at the start of a line escaped too, since any text can start one."
  [s]
  (let [s    (markdown-inline s)
        n    (count s)
        lead (tokenizer/char-at s n (tokenizer/skip-whitespace s n 0))]
    (if (and lead (or (tokenizer/digit? lead) (#{\# \+ \= \-} lead)))
      (str/replace s
                   block-start
                   (fn [[_ space marker digits end]]
                     (if marker
                       (str space "\\" marker)
                       (str space digits "\\" end))))
      s)))

;; CommonMark 0.31.2, section 6.3
(defn- markdown-url
  "The URL `s` as the destination of a Markdown link: without the tabs and
  line breaks that a browser drops, with its spaces encoded, and with what
  would end it or start an escape or a reference escaped."
  [s]
  (-> (str/replace s #"[\t\n\r]" "")
      (str/replace " " "%20")
      (str/escape {\\ "\\\\" \( "\\(" \) "\\)" \< "\\<" \> "\\>"})
      (str/replace reference-start (constantly "\\&"))))

(defn- backticks
  "The backticks around the code `s` in a span or a fence: one more than
  its longest run of them, and at least `n`."
  [s n]
  (let [longest (reduce max 0 (map count (re-seq #"`+" s)))]
    (apply str (repeat (max n (inc longest)) "`"))))

;; CommonMark 0.31.2, section 6.1: a span drops one space from each end
;; when both ends have one
(defn- code-span
  "The code `s` as a Markdown code span, with a space inside each end when
  it starts or ends with a backtick, or starts and ends with a space."
  [s]
  (let [ticks (backticks s 1)
        pad   (if (or (str/starts-with? s "`")
                      (str/ends-with? s "`")
                      (and (str/starts-with? s " ") (str/ends-with? s " ")))
                " "
                "")]
    (str ticks pad s pad ticks)))

(def ^:no-doc emphasis
  "The Markdown marks around the text of each element of emphasis."
  {:strong "**" :b "**" :em "*" :i "*" :s "~~" :strike "~~" :del "~~"})

;; CommonMark 0.31.2, section 2.1: the space separators of Unicode, and
;; the tab, line feed, form feed and carriage return
(defn- unicode-space?
  "Whether the character `c` is Unicode whitespace."
  [c]
  (or (whitespace? c)
      (contains? #{\u00A0 \u1680 \u202F \u205F \u3000} c)
      (<= 0x2000 (tokenizer/code c) 0x200A)))

;; CommonMark 0.31.2, sections 6.2 and 6.7: emphasis doesn't start or end
;; at Unicode whitespace, and a hard line break at either end of a block
;; is a backslash
(defn- emphasized
  "The Markdown `inner` with the `mark` of emphasis around it, inside the
  Unicode whitespace and the line breaks at its ends."
  [mark inner]
  (let [n      (count inner)
        at     #(tokenizer/char-at inner n %)
        space? #(unicode-space? (at %))
        break? #(and (identical? \\ (at %)) (identical? \newline (at (inc %))))
        start  (loop [i 0]
                 (cond
                   (and (< i n) (space? i))       (recur (inc i))
                   (and (< (inc i) n) (break? i)) (recur (+ i 2))
                   :else                          i))
        end    (loop [i n]
                 (cond
                   (and (< (inc start) i) (break? (- i 2))) (recur (- i 2))
                   (and (< start i) (space? (dec i)))       (recur (dec i))
                   :else                                    i))]
    (if (= start end)
      inner
      (str (subs inner 0 start) mark (subs inner start end) mark
           (subs inner end)))))

(defn- block-text
  "The Markdown `inner` of a block without the line breaks and the
  whitespace at its ends."
  [inner]
  (trim-whitespace
   (cond-> inner
     (str/includes? inner "\\\n")
     (-> (str/replace #"^(?:[\t\n\f\r ]*\\\n)+" "")
         (str/replace #"(?:\\\n[\t\n\f\r ]*)+$" "")))))

(defn- markdown-element
  "The Markdown of the element of `tag` and `attrs`, whose children render
  as `inner`, in the context `ctx` of render.

  An element inside a pre or a code element is its text, and a quote
  deeper than the :max-quotes of the context is a paragraph."
  [[tag attrs] inner ctx]
  (let [heading (when-let [[_ n] (re-matches #"h([1-6])" (name tag))]
                  (parse-long n))
        inside? (or (< (if (= :pre tag) 1 0) (:pre ctx 0))
                    (< (if (= :code tag) 1 0) (:code ctx 0)))
        safe?   #(and (string? %) (safe-url? (:allowed-schemes ctx) %))
        quoted  #(->> (str/split-lines %)
                      (map (fn [line] (str "> " line)))
                      (str/join "\n"))]
    (cond
      inside?
      (case tag
        :br  "\n"
        :img (collapse (str (:alt attrs)))
        inner)

      heading
      (str "\n\n" (apply str (repeat heading "#")) " "
           (block-text inner) "\n\n")

      (= :br tag)
      "\\\n"

      (= :hr tag)
      "\n\n---\n\n"

      (= :img tag)
      (let [src (:src attrs)
            alt (markdown-inline (collapse (str (:alt attrs))))]
        (if (safe? src)
          (str "![" alt "](" (markdown-url src) ")")
          alt))

      (= :a tag)
      (let [href (:href attrs)]
        (if (and (safe? href) (not (blank? inner)))
          (str "[" inner "](" (markdown-url href) ")")
          inner))

      (emphasis tag)
      (emphasized (emphasis tag) inner)

      (= :code tag)
      (if (blank? inner) inner (code-span inner))

      (= :pre tag)
      (let [code  (trim-whitespace inner)
            fence (backticks code 3)]
        (str "\n\n" fence "\n" code "\n" fence "\n\n"))

      (= :blockquote tag)
      (if (< (:max-quotes ctx) (:quotes ctx))
        (str "\n\n" inner "\n\n")
        (str "\n\n" (quoted (block-text inner)) "\n\n"))

      (= :li tag)
      (str (list-marker ctx) (block-text inner) "\n")

      (#{:td :th} tag)
      (str (block-text inner) " | ")

      (= :tr tag)
      (str "| " inner "\n")

      ((:paragraph-tags ctx) tag)
      (str "\n\n" (block-text inner) "\n\n")

      ((:line-tags ctx) tag)
      (str (block-text inner) "\n")

      :else
      inner)))

(defn markdown
  "The HTML text or Hiccup `x` as Markdown, by the `opts` of
  default-options, which a Markdown renderer shows as a browser shows the
  HTML. In the Markdown:

  - headings, emphasis, links, images, lists, quotes, code and rules take
    their Markdown forms, and quotes nest at most :max-quotes deep
  - a line break becomes a backslash at the end of the line
  - text is escaped where Markdown would read it as markup
  - a link or an image is its text when the scheme of its URL isn't one of
    :allowed-schemes
  - paragraphs, line breaks and control characters are as text has them
  - everything else is its text"
  ([x]
   (markdown x {}))
  ([x opts]
   (let [opts (options opts)
         md   (render (nodes-of x opts)
                      (fn [s ctx]
                        (cond
                          (:pre ctx)  s
                          (:code ctx) (collapse s)
                          :else       (markdown-text (collapse s))))
                      markdown-element
                      opts)]
     ;; two line breaks in a row, which Markdown has no way to write, as
     ;; the end of a paragraph, and a line break before a block or at the
     ;; end, outside any element
     (cond-> md
       (str/includes? md "\\")
       (-> (str/replace #"(?<!\\)\\\n(?:\\\n)+" "\n\n")
           (str/replace #"(?<!\\)\\(?=\n\n|$)" ""))))))

(def ^:no-doc text-escapes
  "The characters that text in HTML escapes, by character."
  {\& "&amp;" \< "&lt;" \> "&gt;"})

(def ^:no-doc value-escapes
  "The characters that an attribute value in double quotes escapes, by
  character."
  (assoc text-escapes \" "&quot;"))

;; HTML Standard, 13.1.2.3, attribute names, whose rule holds the names
;; of elements to it as well
(defn- html-name
  "The name of the keyword or string `k` when HTML can write it as the
  name of an element or an attribute, or else nil."
  [k]
  (when (or (keyword? k) (string? k))
    (let [s (name k)]
      (when-not (or (= "" s) (re-find #"[\x00-\x20\x7F-\x9F\"'/=>]" s))
        s))))

(defn- attributes-html
  "The attributes `attrs` as HTML, without those whose name HTML can't
  hold, or whose value is false or nil."
  [attrs]
  (str/join (for [[k v] attrs
                  :let  [n (html-name k)]
                  :when (and n (some? v) (not (false? v)))]
              (if (true? v)
                (str " " n)
                (str " " n "=\"" (str/escape (str v) value-escapes) "\"")))))

(defn emit
  "The Hiccup `nodes` as an HTML string, with the text escaped and the void
  elements without end tags. HTML or plain text in a string is first made
  Hiccup by hiccup with `opts`, and so sanitized.

  Hiccup is written as it's given, except for a name that HTML can't hold:
  such an attribute is left out, and such an element is replaced by its
  children."
  ([nodes]
   (emit nodes {}))
  ([nodes opts]
   (letfn [(node [x]
             (cond
               (string? x)        (str/escape x text-escapes)
               (not (element? x)) ""
               :else
               (let [[tag attrs children] (parts x)
                     inner #(str/join (map node children))]
                 (if-let [name (html-name tag)]
                   (let [start (str "<" name (attributes-html attrs) ">")]
                     (if (void-elements tag)
                       start
                       (str start (inner) "</" name ">")))
                   (inner)))))]
     (str/join (map node (if (string? nodes)
                           (hiccup nodes opts)
                           (nodes-of nodes opts)))))))

#?(:clj
   (comment
     (parse "<p>Hello <b>world</b><br>Line two<ul><li>one<li>two</ul>")
     (hiccup "Plain text\n\nwith two paragraphs\nand a break")
     (text (str "<p>Hello <a href=\"https://x\">link</a></p>"
                "<ul><li>one</li><li>two</li></ul>")
           {:links? true})
     (markdown "<h2>Notes</h2><p>Hello <b>world</b></p>")
     #_.))

(ns dk.simongray.html-pieces
  "Embedded pieces of HTML, such as comments, the descriptions in feeds or
  the fields of a CMS, read into Hiccup and rendered from there.

  TODO: an optional report of what parsing discarded and sanitizing
  removed, e.g. in metadata, so that a validator can tell the author of
  the HTML what a client won't show.

  TODO: in the browser, an option to parse with the browser's own
  DOMParser and read its DOM as Hiccup, as hickory does, which would keep
  the tokenizer, the tree builder and the table of character references
  out of the bundle. It would come at a cost:

  - the result could differ from the JVM's and Node's, since a browser
    follows every rule of tree construction, e.g. it carries formatting on
    past a misnested end tag, moves the content of tables, and rearranges
    the html, head and body of a whole page
  - Node has no DOM, so the tokenizer would still be needed there
  - DOMParser reads a whole document, so a fragment would be the children
    of its body, and :max-depth would have to be applied while reading the
    DOM"
  (:require [clojure.string :as str]
            [dk.simongray.html-pieces.commonmark :as commonmark]
            [dk.simongray.html-pieces.render :as render]
            [dk.simongray.html-pieces.serializer :as serializer]
            [dk.simongray.html-pieces.tokenizer :as tokenizer]
            [dk.simongray.html-pieces.tree :as tree]
            [dk.simongray.html-pieces.url :as url]
            [dk.simongray.html-pieces.whitespace :as whitespace]))

;; whole tags only, so that e.g. an email address in angle brackets or a
;; less-than sign before a 3 stays plain text
(def ^:no-doc markup
  "The start of a comment, or a whole tag."
  (re-pattern (str "<!--"
                   "|<[a-zA-Z][a-zA-Z0-9]*(?:[\\t\\n\\f\\r ][^<>]*)?/?>"
                   "|</[a-zA-Z][a-zA-Z0-9]*[\\t\\n\\f\\r ]*>")))

(defn markup?
  "Whether the text `s` is HTML rather than plain text: whether it holds a
  comment or a whole tag."
  [s]
  (boolean (re-find markup (str s))))

(def max-depth
  "How deep elements nest at most. An element opened deeper is kept empty,
  and its content goes to its parent, so that no walk of the tree
  overflows the stack."
  128)

(def allowed-tags
  "The elements that are safe to render."
  #{:p :br :a :strong :b :em :i :u :s :strike :del :ins :mark :small :sup :sub
    :ul :ol :li :dl :dt :dd :h1 :h2 :h3 :h4 :h5 :h6 :blockquote :q :cite :abbr
    :dfn :code :kbd :pre :hr :img :span :div :figure :figcaption
    :table :thead :tbody :tfoot :tr :td :th :caption})

(def dropped-tags
  "The elements that are removed with their content."
  #{:script :style :iframe :object :embed :form :input :button :select :option
    :textarea :noscript :svg :math :template :head :title :meta :link :base
    :frame :frameset :applet :audio :video :canvas})

(def allowed-attributes
  "The attributes that are safe to render, by element."
  {:a          #{:href :title :rel :hreflang}
   :img        #{:src :alt :title :width :height}
   :td         #{:colspan :rowspan}
   :th         #{:colspan :rowspan :scope}
   :ol         #{:start :reversed}
   :blockquote #{:cite}
   :q          #{:cite}
   :abbr       #{:title}
   :dfn        #{:title}})

;; rel-me and rel-author of microformats, rel-license, Webmention 3.1.2
;; (2017-01-12), Pingback 1.0, IndieAuth 4.1 (2024-07-11) and Micropub 5.3
;; (2017-05-23). RelMeAuth and Mastodon trust a rel=me anywhere on a page,
;; and Webmention reads a elements. IndieAuth and Micropub read link
;; elements alone, but the rels of a microformats parser include a elements.
(def dropped-rels
  "The link types that are taken out of a rel, since they'd speak for the
  page that shows the HTML: me, which says that the link goes to another
  profile of the page's owner, author, license, and the endpoints that a
  page names for Webmention, Pingback, IndieAuth, Micropub and Microsub."
  #{"me" "author" "license" "webmention" "pingback" "authorization_endpoint"
    "token_endpoint" "indieauth-metadata" "micropub" "microsub"})

;; HTML Standard, "Index", the attributes whose value is a URL, with
;; background and longdesc of older pages and xlink:href of SVG
(def url-attributes
  "The attributes whose value is a URL."
  #{:action :background :cite :data :formaction :href :itemid :longdesc
    :manifest :poster :src :xlink:href})

(def url-list-attributes
  "The attributes whose value is a list of URLs, separated by spaces, or by
  commas in a srcset."
  #{:itemtype :ping :srcset})

(def allowed-schemes
  "The schemes of the URLs that are safe to keep or show as links."
  #{"http" "https" "mailto"})

(def paragraph-tags
  "Elements set off by a blank line in text and Markdown."
  #{:p :h1 :h2 :h3 :h4 :h5 :h6 :blockquote :pre :ul :ol :dl :table :hr :figure})

(def line-tags
  "Elements that end a line in text and Markdown."
  #{:div :li :tr :dt :dd :figcaption :address :caption :thead :tbody :tfoot
    :section :article})

(def max-quote-depth
  "How deep quotes nest in Markdown at most. A deeper quote is a plain
  paragraph, so that no line starts with hundreds of >."
  16)

;; HTML Standard, 4.6.1 (2026-10-09): the elements that take a rel
(def link-tags
  "The elements that link somewhere when they have an href."
  #{:a :area :link})

(def default-options
  "The default of each option. Most are vars of the same name, and the
  others are:

  - :base-url, the URL of the page that the HTML came from, which each
    URL is resolved against
  - :url-fn, a function of a URL, once it's resolved, that gives the URL
    to keep, or nil
  - :element-fn, a function of an element that's kept, which gives the
    Hiccup to put in its place
  - :trusted-element-fn?, true to keep what :element-fn gives unchecked
  - :heading-level, the level that hiccup moves the highest heading to,
    e.g. 3 on a page whose own headings are h1 and h2
  - :links?, true to put the URL of each link after it in plain text
  - :quirks?, true to read a C1 control character as windows-1252 in text
    and Markdown, a repair that the HTML Standard doesn't make
  - :shorthand?, true to read a tag of Hiccup such as :p.x as a p with a
    class, or false to read each tag as it's written, as in the Hiccup
    that parse gives
  - :plain-text?, true to read a string as plain text even when it holds
    a tag, with its character references kept as they're written
  - :rel, a link type, for links to take only the elements whose rel has
    it
  - :body?, false for links to take only the elements in the head of a
    page
  - :ellipsis, what excerpt puts where it cuts the text"
  {:max-depth           max-depth
   :allowed-tags        allowed-tags
   :dropped-tags        dropped-tags
   :allowed-attributes  allowed-attributes
   :dropped-rels        dropped-rels
   :allowed-schemes     allowed-schemes
   :url-attributes      url-attributes
   :url-list-attributes url-list-attributes
   :base-url            nil
   :url-fn              identity
   :element-fn          nil
   :trusted-element-fn? false
   :heading-level       nil
   :paragraph-tags      paragraph-tags
   :line-tags           line-tags
   :max-quote-depth     max-quote-depth
   :links?              false
   :quirks?             true
   :shorthand?          true
   :plain-text?         false
   :link-tags           link-tags
   :rel                 nil
   :body?               true
   :ellipsis            "…"})

(defn- options
  "The `opts` with the defaults of the keys that they leave out or give as
  nil."
  [opts]
  (if (empty? opts)
    default-options
    (into default-options (remove (comp nil? val)) opts)))

;; The base is read once for every URL of a call. Only the functions that
;; keep URLs call this, so that an app which only parses leaves the
;; resolver out of its bundle.
(defn- resolving
  "The `opts` with each URL resolved against their :base-url, if any,
  before it's given to their :url-fn."
  [{:keys [base-url url-fn] :as opts}]
  (if base-url
    (let [resolve (url/resolver base-url)]
      (assoc opts :url-fn #(some-> (resolve %) url-fn)))
    opts))

(defn parse
  "The HTML or Hiccup `x`, read by `opts`, as a sequence of Hiccup nodes:
  text, and elements that each have an attribute map. Nothing is
  sanitized, so for HTML to render, use hiccup or sanitize.

  HTML is nested at most as deep as :max-depth. Unclosed elements close
  where a browser closes them, but formatting isn't carried on past a
  misnested end tag. The html, head and body elements of a whole page stay
  as they're written. Comments and DOCTYPEs are left out.

  Hiccup is read as every Hiccup renderer reads it, e.g. a tag such as
  :p.x is a p with a class. When :shorthand? is false, each tag is read as
  it's written, as it should be in the Hiccup that parse gives, e.g. the
  :x-a.b of <x-a.b>."
  ([x]
   (parse x {}))
  ([x opts]
   (let [{:keys [max-depth shorthand?]} (options opts)]
     (if (string? x)
       (tree/build (tokenizer/tokens x) max-depth)
       (apply list (mapcat #(tree/canonical % shorthand?) (tree/nodes x)))))))

(defn serialize
  "The HTML or Hiccup `x`, read by `opts`, as HTML that's written as a
  browser writes it, by 13.3 of the HTML Standard. Nothing is sanitized,
  so for HTML to render, use sanitize."
  ([x]
   (serialize x {}))
  ([x opts]
   (serializer/html (parse x opts) serializer/raw-text-elements)))

(defn- tag-of
  "The tag of the parsed `node`, or nil for text."
  [node]
  (when (vector? node)
    (node 0)))

;; HTML Standard, 4.12.3 (2026-10-09): the content of a template is a
;; fragment of its own, outside the page
(defn- page-elements
  "The elements of the parsed `nodes` at any depth, in document order,
  without the content of templates."
  [nodes]
  (->> nodes
       (mapcat #(tree-seq (fn [node]
                            (and (vector? node) (not= :template (node 0))))
                          (fn [node]
                            (subvec node 2))
                          %))
       (filter vector?)))

(defn- unwrapped
  "The parsed `nodes` with each element of the `tag` replaced by its
  children."
  [tag nodes]
  (mapcat #(if (= tag (tag-of %)) (subvec % 2) [%]) nodes))

;; HTML Standard, 13.2.6.4.4 (2026-10-09): the elements of a head go into
;; it until another element or text that isn't whitespace, head tag or not
(defn- page-head
  "The elements in the head of the page with the parsed `nodes`."
  [nodes]
  (->> nodes
       (unwrapped :html)
       (unwrapped :head)
       (remove #(and (string? %) (whitespace/blank? %)))
       (take-while #(contains? tree/head-elements (tag-of %)))))

;; HTML Standard, 2.4.1 and 4.2.3 (2026-10-09): the first base element with
;; an href, in tree order, unless its URL fails or is data: or javascript:
(defn- page-base
  "The base URL of the page with the parsed `nodes` at the URL `page`."
  [nodes page]
  (let [href (some (fn [[tag attrs]]
                     (when (= :base tag)
                       (:href attrs)))
                   (page-elements nodes))
        base (some->> href (url/resolve page))]
    (if (and base (not (contains? #{"data" "javascript"} (url/scheme base))))
      base
      page)))

;; HTML Standard, 4.6.1 (2026-10-09): a set of link types separated by
;; ASCII whitespace, compared ASCII case-insensitively
(defn- link-types
  "The link types of the `rel`, in lower case."
  [rel]
  (into #{}
        (comp (remove str/blank?) (map tokenizer/lower-ascii))
        (str/split (str rel) #"[\t\n\f\r ]+")))

(defn elements
  "The elements of the HTML or Hiccup `x`, read by `opts`, at any depth and
  in document order, without the content of templates, which a browser
  keeps out of the page."
  ([x]
   (elements x {}))
  ([x opts]
   (page-elements (parse x opts))))

(defn head
  "The elements in the head of the page `x`, read by `opts`: the children
  of its head element, and those before the page's content, which a
  browser puts in the head even when there's no head tag."
  ([x]
   (head x {}))
  ([x opts]
   (page-head (parse x opts))))

(defn base-url
  "The URL that the links of the page `x` are relative to, by `opts`: the
  href of its first base element that has one, resolved against :base-url,
  the URL of the page, or else :base-url."
  ([x]
   (base-url x {}))
  ([x opts]
   (page-base (parse x opts) (:base-url opts))))

;; Webmention 3.1.2 (2017-01-12) reads link and a elements anywhere, and
;; IndieAuth 4.1 (2024-07-11) and WebSub 4 (2026-06-02) link elements. The
;; head alone keeps out a link that a comment adds, as indieauth.rocks's
;; test C112 checks, and a base element that it adds too.
(defn links
  "The elements of the page `x` that link somewhere, in document order, by
  `opts`, with each href resolved against the page's base URL, as base-url
  gives it:

  - :link-tags are the elements that count
  - :rel is a link type, to take only the elements whose rel has it
  - :body? false takes only the elements in the head of the page, and the
    base element from there too

  An element whose href doesn't resolve is left out, e.g. a relative one
  when the page has no base element and there's no :base-url.

      (map (comp :href second)
           (links \"<link rel=webmention href=/mention>\"
                  {:base-url \"https://example.com/post\" :rel \"webmention\"}))
      ;; => (\"https://example.com/mention\")"
  ([x]
   (links x {}))
  ([x opts]
   (let [{:keys [link-tags rel body?] :as opts} (options opts)
         nodes   (cond->> (parse x opts)
                   (not body?) page-head)
         resolve (url/resolver (page-base nodes (:base-url opts)))
         rel     (some-> rel tokenizer/lower-ascii)]
     (for [[tag attrs :as element] (if body?
                                     (page-elements nodes)
                                     nodes)
           :when (and (contains? link-tags tag)
                      (contains? attrs :href)
                      (or (nil? rel)
                          (contains? (link-types (:rel attrs)) rel)))
           :let  [href (resolve (:href attrs))]
           :when href]
       (assoc-in element [1 :href] href)))))

(def ^:no-doc structural-tags
  "Elements whose text children are layout whitespace, never content."
  #{:ul :ol :dl :table :thead :tbody :tfoot :tr})

;; HTML Standard, 4.6.1: a set of tokens separated by ASCII whitespace, and
;; link types are ASCII case-insensitive
(defn- kept-rels
  "The `rel` without the link types of `dropped`, or nil when none is
  left."
  [dropped rel]
  (some->> (str/split (str rel) #"[\t\n\f\r ]+")
           (remove #(or (str/blank? %)
                        (contains? dropped (tokenizer/lower-ascii %))))
           seq
           (str/join " ")))

(defn- safe-attributes
  "The attributes `attrs` of the element `tag` that `opts` allows, with
  their URLs checked and the :dropped-rels left out of a rel."
  [opts tag attrs]
  (let [{:keys [allowed-attributes url-attributes url-list-attributes url-fn
                dropped-rels]
         schemes :allowed-schemes} opts]
    (into {} (for [[k v] attrs
                   :when (contains? (get allowed-attributes tag) k)
                   :let  [v (cond
                              (url-attributes k)
                              (url/checked url-fn schemes v)

                              (url-list-attributes k)
                              (url/checked-list url-fn schemes
                                                (= :srcset k) v)

                              (= :rel k)
                              (kept-rels dropped-rels v)

                              :else
                              v)]
                   :when (some? v)]
               [k v]))))

(defn- nodes-of
  "The nodes of `x`, which is a string, a node or nodes. A string is parsed
  with `opts` when it holds markup, or read as paragraphs when it's plain
  text or :plain-text?."
  [x opts]
  (cond
    (not (string? x))   (tree/nodes x)
    (:plain-text? opts) (tree/paragraphs x)
    (markup? x)         (parse x opts)
    :else               (tree/paragraphs (tree/decoded x))))

;; Most HTML has no base element, and is then not walked for one
(defn- readable
  "The nodes of `x` and the full `opts` to render them by, with each URL
  resolved against the base URL of the page, as base-url gives it, when
  `x` is HTML."
  [x opts]
  (let [opts  (options opts)
        nodes (nodes-of x opts)
        base  (if (and (string? x)
                       (not (:plain-text? opts))
                       (re-find #"<[bB][aA][sS][eE][\t\n\f\r />]" x))
                (page-base nodes (:base-url opts))
                (:base-url opts))]
    [nodes (resolving (assoc opts :base-url base))]))

;; What :element-fn gives is sanitized again by the same walk, but without
;; :url-fn and :base-url, which its URLs have been through, and without
;; :element-fn, so that it's called once for each element. An element that
;; it gives back as it is costs nothing more.
(defn- sanitized
  "The `nodes`, built Hiccup when `built?`, with only what `opts` allows."
  [nodes built? opts]
  (let [allowed    (:allowed-tags opts)
        dropped    (:dropped-tags opts)
        element-fn (:element-fn opts)
        trusted?   (:trusted-element-fn? opts)
        again      (when element-fn
                     (assoc opts :element-fn nil :url-fn identity))
        layout?    #(and (string? %) (whitespace/blank? %))]
    (letfn [(node [x]
              (cond
                (string? x)
                [x]

                ;; a tag of HTML can hold a dot or a #, e.g. <p.lead>, so
                ;; only Hiccup that's built has shorthand tags, and only
                ;; when :shorthand?
                (and built?
                     (tree/element? x)
                     (tree/shorthand? (x 0))
                     (:shorthand? opts))
                (node (tree/expanded x))

                (tree/element? x)
                (let [[tag attrs children] (tree/parts x)]
                  (if (dropped tag)
                    []
                    ;; lists and tables lose the whitespace of their layout
                    (let [kids (into []
                                     (comp (mapcat node)
                                           (if (structural-tags tag)
                                             (remove layout?)
                                             identity))
                                     children)
                          safe (safe-attributes opts tag attrs)]
                      (if (allowed tag)
                        (let [element (into [tag safe] kids)]
                          (if element-fn
                            (replaced element)
                            [element]))
                        kids))))

                ;; e.g. of a for, spliced in as every Hiccup renderer does
                (seq? x)
                (into [] (mapcat node) x)

                (number? x)
                [(str x)]

                :else
                []))
            (replaced [element]
              (let [x (element-fn element)]
                (cond
                  (identical? x element) [element]
                  trusted?               (tree/canonical x (:shorthand? opts))
                  :else                  (sanitized [x] true again))))]
      (into [] (mapcat node) nodes))))

(def ^:no-doc heading-levels
  "The level of each heading."
  {:h1 1 :h2 2 :h3 3 :h4 4 :h5 5 :h6 6})

(defn- moved-heading
  "The `tag`, `shift` levels lower when it's a heading, within h1 to h6."
  [tag shift]
  (if-let [level (heading-levels tag)]
    (keyword (str "h" (-> (+ level shift) (max 1) (min 6))))
    tag))

;; The headings of a piece start where its writer chose, often at h1, which
;; would break the outline of the page that shows it.
;; https://www.w3.org/WAI/tutorials/page-structure/headings/
(defn- with-heading-level
  "The sanitized `nodes` with their highest heading at `level`, and the
  others as far below it as before."
  [nodes level]
  (if-let [levels (->> (page-elements nodes)
                       (keep (comp heading-levels tag-of))
                       seq)]
    (let [shift (- level (apply min levels))]
      (letfn [(node [x]
                (if (vector? x)
                  (into [(moved-heading (x 0) shift) (x 1)]
                        (map node)
                        (subvec x 2))
                  x))]
        (mapv node nodes)))
    nodes))

(defn hiccup
  "The HTML, plain text or Hiccup `x` as Hiccup that's safe to render, by
  these keys of `opts`:

  - elements of :allowed-tags are kept, with the attributes of
    :allowed-attributes, but without the :dropped-rels in a rel, e.g. the
    me that would let the writer of a comment sign in as the owner of the
    page
  - elements of :dropped-tags are removed with their content
  - other elements are replaced by their children
  - a URL is resolved against the base URL of the page, as base-url gives
    it, and kept as :url-fn rewrites it when its scheme is one of
    :allowed-schemes, so a relative URL is left out without a base URL
  - an element that's kept, once its attributes and children are safe,
    is given to :element-fn, and replaced by the Hiccup that it returns,
    e.g. a p in place of an h1
  - with a :heading-level, e.g. 3 on a page whose own headings are h1 and
    h2, the highest heading of the result takes that level, and the
    others keep their distance below it, within h1 to h6

  What :element-fn returns is sanitized again, so that it can't bring in
  what isn't allowed, unless :trusted-element-fn? is true. An element
  that it returns as it was given is kept.

  Plain text becomes paragraphs with line breaks. Text is decoded, e.g.
  &lt; is <, so use a renderer that escapes text. With :plain-text?, any
  string is read as plain text, e.g. a summary that can hold a <, and
  nothing in it is decoded."
  ([x]
   (hiccup x {}))
  ([x opts]
   (let [[nodes opts] (readable x opts)
         level        (:heading-level opts)]
     (apply list (cond-> (sanitized nodes (not (string? x)) opts)
                   level (with-heading-level level))))))

;; The text of a script or a style that's allowed is escaped too, unlike in
;; a browser, since text that held its end tag would end it early
(defn sanitize
  "The HTML, plain text or Hiccup `x` as HTML that's safe to render, by the
  `opts` of hiccup."
  ([x]
   (sanitize x {}))
  ([x opts]
   (serializer/html (hiccup x opts) #{})))

(defn text
  "The HTML or Hiccup `x` as plain text, by `opts`. An image is its alt
  text, and a link is followed by its URL when :links? and the URL is
  allowed. Control characters are left out, but for tabs and line breaks."
  ([x]
   (text x {}))
  ([x opts]
   (let [[nodes opts] (readable x opts)]
     (render/walk nodes
                  (not (string? x))
                  render/text-inline
                  render/text-element
                  str/join
                  opts))))

(defn markdown
  "The HTML or Hiccup `x` as Markdown, by `opts`. Text is escaped where
  Markdown would read it as markup, and a link or an image is its text
  when its URL isn't allowed. Control characters are left out, but for
  tabs and line breaks."
  ([x]
   (markdown x {}))
  ([x opts]
   (let [[nodes opts] (readable x opts)]
     (commonmark/fix-breaks (render/walk nodes
                                         (not (string? x))
                                         commonmark/inline
                                         commonmark/element
                                         commonmark/joined
                                         opts)))))

(defn- cut-at
  "The index at which to cut the text `s` to at most `limit` characters:
  at the last space up to there, or else at the limit, but never inside a
  surrogate pair."
  [s limit]
  (let [space (if (= \space (nth s limit))
                limit
                (str/last-index-of s " " limit))]
    (cond
      (and space (pos? space))
      space

      (and (pos? limit)
           #?(:clj  (Character/isHighSurrogate (.charAt ^String s (dec limit)))
              :cljs (<= 0xD800 (.charCodeAt s (dec limit)) 0xDBFF)))
      (dec limit)

      :else
      limit)))

(defn excerpt
  "The text of the HTML or Hiccup `x` as one line of at most `n`
  characters, by `opts`, cut at the end of a word, with the :ellipsis
  where it's cut, or at `n` within a longer word."
  ([x n]
   (excerpt x n {}))
  ([x n opts]
   (let [s        (whitespace/collapse (text x opts))
         ellipsis (:ellipsis (options opts))]
     (if (<= (count s) n)
       s
       (let [limit (- n (count ellipsis))]
         (if (pos? limit)
           (str (whitespace/strip (subs s 0 (cut-at s limit))) ellipsis)
           (subs ellipsis 0 (max 0 n))))))))

#?(:clj
   (comment
     (parse "<p>Hello <b>world</b><br>Line two<ul><li>one<li>two</ul>")
     (hiccup "Plain text\n\nwith two paragraphs\nand a break")
     (sanitize "<p onclick=\"steal()\">Hi <b>there</b></p><script>x</script>")
     (text (str "<p>Hello <a href=\"https://x\">link</a></p>"
                "<ul><li>one</li><li>two</li></ul>")
           {:links? true})
     (markdown "<h2>Notes</h2><p>Hello <b>world</b></p>")
     #_.))

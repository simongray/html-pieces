(ns ^:no-doc dk.simongray.html-pieces.tree
  "The Hiccup tree of a piece of HTML, built from its tokens by the rules of
  tree construction in 13.2.6 of the HTML Standard that such pieces need,
  or from plain text as paragraphs.

  TODO: the rules of tree construction that a browser follows and this
  leaves out, by an option or a namespace of their own: formatting that a
  browser carries on past a misnested end tag, the repairs of tables, and
  SVG and MathML."
  (:require [clojure.string :as str]
            [dk.simongray.html-pieces.tokenizer :as tokenizer]
            [dk.simongray.html-pieces.whitespace :as whitespace])
  #?(:clj (:import [java.util ArrayList HashMap])))

;; HTML Standard, "Elements": the void elements, and param of older pages
(def void-elements
  "Elements that never have content or an end tag."
  #{:area :base :br :col :embed :hr :img :input :link :meta :param :source
    :track :wbr})

;; HTML Standard, "Optional tags", as of 2026-09-29
(def block-elements
  "Elements whose start tag closes an open paragraph, as HTML defines."
  #{:address :article :aside :blockquote :details :dialog :div :dl :fieldset
    :figcaption :figure :footer :form :h1 :h2 :h3 :h4 :h5 :h6 :header :hgroup
    :hr :main :menu :nav :ol :p :pre :search :section :table :ul})

;; HTML Standard, "Optional tags", and 13.2.6.4.7 for a, which closes an
;; open a up to the markers of the list of active formatting elements
(def implied-end
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

(def headings
  "The headings, whose start tag closes a heading that's the current node,
  as 13.2.6.4.7 of the HTML Standard has it."
  #{:h1 :h2 :h3 :h4 :h5 :h6})

(def foreign-roots
  "The roots of SVG and MathML, inside which an element can close itself
  with a slash, as 13.2.6.5 of the HTML Standard has it."
  #{:svg :math})

(def paragraph-scope
  "The elements that bound the search for an open p: block-level ones, list
  items and table cells."
  (into block-elements #{:li :td :th :dt :dd :body}))

(def newline-dropping
  "The elements whose start tag drops a line feed right after it, as
  13.2.6.4.7 of the HTML Standard has it."
  #{:pre :listing :textarea})

(defn element?
  "Whether `x` is a Hiccup element: a vector with a keyword tag."
  [x]
  (and (vector? x) (keyword? (nth x 0 nil))))

(defn parts
  "The tag, the attributes and the children of the Hiccup element `node`,
  whose attribute map can be left out, e.g. [:p \"x\"]."
  [node]
  (if (map? (get node 1))
    [(node 0) (node 1) (subvec node 2)]
    [(node 0) {} (subvec node 1)]))

;; Mutable lists, an ArrayList on the JVM and an array in JavaScript, which
;; the builder adds to in place. They're read with nth and count.

(defn- mutable-list
  "A new, empty mutable list."
  []
  #?(:clj  (ArrayList.)
     :cljs #js []))

(defn- add!
  "The mutable list `l` with `x` added at its end."
  [l x]
  #?(:clj  (.add ^ArrayList l x)
     :cljs (.push l x))
  l)

(defn- set-last!
  "The mutable list `l` with its last item replaced by `x`."
  [l x]
  #?(:clj  (.set ^ArrayList l (int (dec (.size ^ArrayList l))) x)
     :cljs (aset l (dec (alength l)) x))
  l)

(defn- remove-last!
  "Remove the last item of the mutable list `l`, and give it."
  [l]
  #?(:clj  (.remove ^ArrayList l (int (dec (.size ^ArrayList l))))
     :cljs (.pop l)))

;; The tree is built in a builder of the stack of open elements, the root
;; first, how many are open of each tag, so that an end tag nothing
;; matches costs nothing to ignore, the max-depth, and whether a line feed
;; at the start of the next text is dropped. An open element is a mutable
;; list of its tag, its attributes and its children, which becomes a
;; vector when it closes. Each function of the builder changes it in place
;; and gives it back.

(deftype Builder [stack counts max-depth drop-newline?])

(defn- new-builder
  "A builder with only the root open, which nests elements at most
  `max-depth` deep."
  [max-depth]
  (Builder. (add! (mutable-list) (-> (mutable-list) (add! :root) (add! {})))
            #?(:clj (HashMap.) :cljs (js/Map.))
            max-depth
            (volatile! false)))

(defn- top
  "The stack index of the top element of the builder `b`."
  [^Builder b]
  (dec (count (.-stack b))))

(defn- element-at
  "The open element at the stack index `i` of the builder `b`."
  [^Builder b i]
  (nth (.-stack b) i))

(defn- tag-at
  "The tag of the open element at the stack index `i` of the builder `b`."
  [b i]
  (nth (element-at b i) 0))

(defn- too-deep?
  "Whether the open elements of the builder `b` are nested as deep as its
  max-depth allows, or deeper."
  [^Builder b]
  (> (count (.-stack b)) (.-max-depth b)))

(defn- drop-newline!
  "The builder `b`, set to drop a line feed at the start of the next text
  when `drop?`."
  [^Builder b drop?]
  (vreset! (.-drop-newline? b) drop?)
  b)

;; counted by the name of the tag, since two ClojureScript keywords of the
;; same name needn't be identical
(defn- open-count
  "How many elements of `tag` are open in the builder `b`."
  [^Builder b tag]
  (or #?(:clj  (.get ^HashMap (.-counts b) (name tag))
         :cljs (.get (.-counts b) (name tag)))
      0))

(defn- open?
  "Whether an element of one of `tags` is open in the builder `b`."
  [b tags]
  (some #(pos? (open-count b %)) tags))

(defn- update-count!
  "The builder `b` with the count of the open elements of `tag` changed by
  `f`."
  [^Builder b tag f]
  (let [n (f (open-count b tag))]
    #?(:clj  (.put ^HashMap (.-counts b) (name tag) n)
       :cljs (.set (.-counts b) (name tag) n)))
  b)

(defn- append!
  "The builder `b` with `child` appended to its top element, and joined to
  the text before it when both are text."
  [b child]
  (let [element (element-at b (top b))
        before  (nth element (dec (count element)))]
    (if (and (string? child) (string? before))
      (set-last! element (str before child))
      (add! element child))
    b))

(defn- push!
  "The builder `b` with an element of `tag` and `attrs` opened on top."
  [^Builder b tag attrs]
  (add! (.-stack b) (-> (mutable-list) (add! tag) (add! attrs)))
  (update-count! b tag inc))

;; vec takes over a small array in ClojureScript, which is safe since a
;; closed element is never changed again
(defn- close-top!
  "The builder `b` with its top element closed into its parent."
  [^Builder b]
  (let [node (vec (remove-last! (.-stack b)))]
    (-> b
        (update-count! (node 0) dec)
        (append! node))))

(defn open-index
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
  [^Builder b tags boundaries]
  (if-let [i (open-index b tags boundaries)]
    (loop [b b k (- (count (.-stack b)) i)]
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
            (too-deep? b))
      (append! b [tag attrs])
      (push! b tag attrs))))

(def nul-text
  "U+0000 NULL as a text, which the tree builder drops from text."
  (str tokenizer/nul))

;; ClojureScript makes a new keyword on each call, while the JVM interns
;; them. A limit, as in jsoup's cache of names, keeps made-up names from
;; filling the memory.
#?(:cljs
   (def name-keywords
     "The keyword of each element or attribute name that's been read, by
     name, up to a limit."
     (js/Map.)))

(defn name-keyword
  "The keyword of the element or attribute name `s`."
  [s]
  #?(:clj  (keyword s)
     :cljs (or (.get name-keywords s)
               (let [k (keyword s)]
                 (when (< (.-size name-keywords) 512)
                   (.set name-keywords s k))
                 k))))

(defn shorthand?
  "Whether the Hiccup `tag` is shorthand for an id or a class, e.g. :p.x."
  [tag]
  (let [s (name tag)]
    (or (str/includes? s ".") (str/includes? s "#"))))

;; e.g. :div#main.intro.lead, with the id before the classes, as every
;; Hiccup renderer reads it
(defn shorthand
  "The tag, the id and the class that the shorthand `tag` stands for, e.g.
  :p, \"x\" and \"a b\" for :p#x.a.b."
  [tag]
  (let [s    (name tag)
        dot  (str/index-of s ".")
        hash (str/index-of s "#")
        id?  (and hash (or (nil? dot) (< hash dot)))]
    [(name-keyword (subs s 0 (if id? hash dot)))
     (when id? (subs s (inc hash) (or dot (count s))))
     (when dot (str/replace (subs s (inc dot)) "." " "))]))

;; A :class of the attributes is added to the shorthand's, and an :id of
;; them wins over its id, as in hiccup, huff and Reagent, though not in
;; Replicant
(defn expanded
  "The Hiccup element `node`, whose tag is shorthand, with its tag, id and
  class written out, e.g. [:p#x.a \"y\"] as [:p {:id \"x\" :class \"a\"} \"y\"]."
  [node]
  (let [[tag id class]     (shorthand (node 0))
        [_ attrs children] (parts node)]
    (into [tag (cond-> attrs
                 (and id (nil? (:id attrs)))
                 (assoc :id id)

                 class
                 (assoc :class (if-some [more (:class attrs)]
                                 (str class " " more)
                                 class)))]
          children)))

;; update-keys makes a transient even when there are no attributes
(defn- attributes
  "The attributes `attrs` of a start tag token, by keyword."
  [attrs]
  (cond-> attrs
    (seq attrs) (update-keys name-keyword)))

(defn- step!
  "The builder `b` after the `token`, as a few of the rules of 13.2.6.4.7
  of the HTML Standard build the tree."
  [^Builder b token]
  (let [drop-newline? @(.-drop-newline? b)
        b             (drop-newline! b false)]
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
            tag                                (name-keyword name)]
        (cond-> (open-element! b tag (attributes attrs) self-closing?)
          (newline-dropping tag) (drop-newline! true)))

      ;; an end tag br is a line break
      (= :end-tag (:type token))
      (let [tag (name-keyword (:name token))]
        (if (= :br tag)
          (open-element! b :br {} false)
          (close-through! b #{tag} #{})))

      :else
      b)))

(defn build
  "The Hiccup nodes that the HTML `tokens` build, nested at most
  `max-depth` deep."
  [tokens max-depth]
  (loop [b (reduce step! (new-builder max-depth) tokens)]
    (if (zero? (top b))
      (apply list (drop 2 (element-at b 0)))
      (recur (close-top! b)))))

(defn- decoded
  "The text `s` with its character references decoded and every other
  character kept, as HTML reads the text of a title: the RCDATA state of
  13.2.5.2 of the HTML Standard."
  [s]
  (apply str (tokenizer/tokens s {:state :rcdata})))

(defn paragraphs
  "The plain text `s` as paragraphs, with blank lines between them and line
  breaks within."
  [s]
  (let [text (whitespace/strip (decoded s))]
    (apply list (for [paragraph (str/split text #"\n[\t\n\f\r ]*\n")
                      :let  [lines (->> (str/split-lines paragraph)
                                        (map whitespace/strip)
                                        (remove whitespace/blank?))]
                      :when (seq lines)]
                  (into [:p {}] (interpose [:br {}] lines))))))

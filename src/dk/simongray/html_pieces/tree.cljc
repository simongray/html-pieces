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
            [dk.simongray.html-pieces.whitespace :as whitespace]))

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

(defn merge-text
  "The element `node` with each run of adjacent strings among its children
  joined into one."
  [node]
  (let [text? #(string? (nth node %))
        ;; only a comment or a stray end tag between two texts leaves a
        ;; run, so most elements are returned as they are
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

;; The tree is built in a builder, a transient map of :stack, the open
;; elements, the root first, each a transient vector [tag attrs &
;; children], :open, how many are open by tag, so that an end tag nothing
;; matches costs nothing to ignore, and :max-depth. Each function of the
;; builder takes it and gives it back, through the values that assoc!,
;; conj! and pop! return. Tags are the keywords of Hiccup.

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

(def nul-text
  "U+0000 NULL as a text, which the tree builder drops from text."
  (str tokenizer/nul))

(defn- step!
  "The builder `b` after the `token`, as a few of the rules of 13.2.6.4.7
  of the HTML Standard build the tree."
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

(defn build
  "The Hiccup nodes that the HTML `tokens` build, nested at most
  `max-depth` deep."
  [tokens max-depth]
  (let [builder (transient {:stack     (transient [(transient [:root {}])])
                            :open      (transient {})
                            :max-depth max-depth})]
    (loop [b (reduce step! builder tokens)]
      (if (= 1 (count (:stack b)))
        (apply list (drop 2 (merge-text (persistent! (nth (:stack b) 0)))))
        (recur (close-top! b))))))

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

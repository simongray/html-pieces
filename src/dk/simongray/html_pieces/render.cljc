(ns ^:no-doc dk.simongray.html-pieces.render
  "Hiccup rendered as text: a walk that renders each text and element by
  the functions that it's given, and the functions for plain text."
  (:require [clojure.string :as str]
            [dk.simongray.html-pieces.entities :as entities]
            [dk.simongray.html-pieces.tokenizer :as tokenizer]
            [dk.simongray.html-pieces.tree :as tree]
            [dk.simongray.html-pieces.url :as url]
            [dk.simongray.html-pieces.whitespace :as whitespace]))

(def pre-mark
  "The mark at the start of each line of preformatted text while it's
  rendered, so that its indentation is kept. It's a character of the
  private use area, which no text holds."
  "\uE000")

(defn- marked-lines
  "The preformatted text `s` with a mark at the start of each line, and
  without the line breaks at its end."
  [s]
  (let [end (loop [i (count s)]
              (if (and (pos? i) (contains? #{\newline \return} (nth s (dec i))))
                (recur (dec i))
                i))]
    (str pre-mark (str/replace (subs s 0 end) "\n" (str "\n" pre-mark)))))

(def indent-mark
  "The mark of one space of the indentation of a line in a list item while
  it's rendered, so that trimming the line keeps it. It's a character of
  the private use area, which no text holds."
  "\uE001")

(defn indented
  "The rendered `s` with `width` marks of indentation at the start of each
  line, the first line too when `first?`."
  [s width first?]
  (let [indent (apply str (repeat width indent-mark))]
    (cond-> (str/replace s "\n" (str "\n" indent))
      first? (->> (str indent)))))

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
;; tabs and line feeds as boxes, if at all. A terminal or a notification
;; could act on them, so they're left out.
(defn- shown
  "The text `s` with each carriage return as a space, and without the
  other control characters but tabs and line feeds. With `quirks?`, a C1
  control is the character of windows-1252 that it stands for, as for a
  numeric reference in 13.2.5.84 of the HTML Standard."
  [s quirks?]
  (if (controls? s)
    (cond-> (str/replace s "\r" " ")
      ;; a C1 control is mostly a character of windows-1252 read as Latin-1
      quirks?
      (str/replace #"[\x80-\x9F]"
                   #(if-let [x (get tokenizer/c1-replacements
                                    (tokenizer/code (first %)))]
                      (entities/code-point->string x)
                      ""))

      :always
      (str/replace #"[\x00-\x08\x0B\x0C\x0E-\x1F\x7F-\x9F]" ""))
    s))

;; split by a one-character string, which the JVM does without a regex;
;; the text has no carriage returns once shown
(defn- lines
  "The lines of the text `s`."
  [s]
  #?(:clj  (.split ^String s "\n")
     :cljs (.split s "\n")))

(defn- trimmed-line
  "The `line` without the whitespace at its ends, unless it's a line of
  preformatted text, and empty when that's all it has. The marks of
  indentation at its start stay."
  [line]
  (let [n     (count line)
        mark  (tokenizer/code-at indent-mark 0)
        start (loop [i 0]
                (if (and (< i n) (== mark (tokenizer/code-at line i)))
                  (recur (inc i))
                  i))
        body  (cond-> line (pos? start) (subs start))]
    (if (str/starts-with? body pre-mark)
      line
      (let [text (whitespace/strip body)]
        (if (or (zero? start) (= "" text))
          text
          (str (subs line 0 start) text))))))

(def squeeze-blanks-xf
  "A transducer of lines that cuts each run of empty lines to one."
  (comp (partition-by #(= "" %))
        (mapcat #(if (= "" (first %)) [""] %))))

(defn- tidy
  "The rendered `s` without control characters, by `quirks?`, trimmed, and
  with each run of blank lines cut to one. Its lines are trimmed too,
  except those of preformatted text."
  [s quirks?]
  (let [text (->> (lines (shown s quirks?))
                  (into [] (comp (map trimmed-line) squeeze-blanks-xf))
                  (str/join "\n")
                  (whitespace/strip))]
    (cond-> text
      (str/includes? text pre-mark)    (str/replace pre-mark "")
      (str/includes? text indent-mark) (str/replace indent-mark " "))))

(def emphasis-kinds
  "The kind of each element of emphasis: the elements of a kind look the
  same."
  {:strong :strong :b :strong :em :em :i :em :s :s :strike :s :del :s})

;; HTML Standard, 2.3.4.1 (2026-10-09): the rules for parsing integers
(defn- integer-of
  "The integer that the attribute value `s` starts with, or nil."
  [s]
  (some->> (str s)
           (re-find #"^[\t\n\f\r ]*([+-]?[0-9]+)")
           second
           parse-long))

(defn- item-count
  "The number of li elements among the `children`, with a sequence of them
  spliced in."
  [children]
  (reduce (fn [n x]
            (cond
              (seq? x)                                 (+ n (item-count x))
              (and (tree/element? x) (= :li (nth x 0))) (inc n)
              :else                                    n))
          0
          children))

(defn- marker-width
  "The width of the marker of the last item of the list in the context
  `ctx`."
  [{:keys [list index step]}]
  (if (= :ol list)
    (+ 2 (count (str (- @index step))))
    2))

;; HTML Standard, 4.4.5 and 4.4.8 (2026-10-09): an ol counts up from its
;; start, or down from it when reversed, from 1 or from the number of its
;; items by default
(defn- in-list
  "The context `ctx` inside the list of `tag` and `attrs`, whose children
  are the `children`."
  [ctx tag attrs children]
  (let [reversed? (and (= :ol tag) (contains? attrs :reversed))
        start     (or (when (= :ol tag) (integer-of (:start attrs)))
                      (if reversed? (item-count children) 1))]
    (assoc ctx
           :list   tag
           :index  (volatile! start)
           :start  start
           :step   (if reversed? -1 1)
           :item?  false
           :nested (cond
                     (:item? ctx) :item
                     (:list ctx)  (marker-width ctx)))))

(defn walk
  "The `nodes`, built Hiccup when `built?`, rendered as text by the
  functions `inline-fn`, `element-fn` and `join-fn`, starting from the map
  `context` of options.

  The `inline-fn` takes a string and the context, the `element-fn` takes
  a vector of the tag and attributes, the rendered children and the
  context, and the `join-fn` takes a vector of nodes next to each other,
  rendered. The nodes themselves render as an element whose tag is nil.
  Inside an element, the context also has:

  - :list, :ul or :ol inside a list
  - :index, a volatile of the number of the next list item, from :start
    by :step, 1 or -1
  - :item?, true inside a list item, but not inside a list in it
  - :nested, for a list inside another, :item inside a list item, or the
    width of the marker before it when it's right inside the other list
  - :pre, :code and :quotes, how many pre, code and blockquote elements
    it's in, the element itself included
  - :strong, :em and :s, how many elements of each kind of emphasis it's
    in, e.g. em or i, the element itself included"
  [nodes built? inline-fn element-fn join-fn context]
  (letfn [(node [x outer]
            (cond
              (string? x)
              (inline-fn x outer)

              ;; a tag of HTML can hold a dot or a #, e.g. <p.lead>, so only
              ;; Hiccup that's built has shorthand tags, and only when
              ;; :shorthand?
              (and built?
                   (tree/element? x)
                   (tree/shorthand? (x 0))
                   (:shorthand? outer))
              (node (tree/expanded x) outer)

              (tree/element? x)
              (let [[tag attrs children] (tree/parts x)
                    counted              #(update %1 %2 (fnil inc 0))]
                (if ((:dropped-tags outer) tag)
                  ""
                  (let [ctx   (cond-> outer
                                (#{:ul :ol} tag)
                                (in-list tag attrs children)

                                (= :li tag)         (assoc :item? true)
                                (= :pre tag)        (counted :pre)
                                (= :code tag)       (counted :code)
                                (= :blockquote tag) (counted :quotes)

                                (emphasis-kinds tag)
                                (counted (emphasis-kinds tag)))
                        inner (join-fn (into [] (map #(node % ctx)) children))]
                    (element-fn [tag attrs]
                                (if (and (= :pre tag) (not (:pre outer)))
                                  (marked-lines inner)
                                  inner)
                                ctx))))

              ;; e.g. of a for, spliced in as every Hiccup renderer does
              (seq? x)
              (join-fn (into [] (map #(node % outer)) x))

              (number? x)
              (inline-fn (str x) outer)

              :else
              ""))]
    (tidy (element-fn [nil {}]
                      (join-fn (into [] (map #(node % context)) nodes))
                      context)
          (:quirks? context))))

;; HTML Standard, 4.4.8 (2026-10-09): the value of an item sets its number,
;; and the next items count on from it
(defn list-number
  "The number of the list item with `attrs` in the ordered list of the
  context `ctx`, which counts on from it."
  [attrs {:keys [index step]}]
  (let [n (or (integer-of (:value attrs)) @index)]
    (vreset! index (+ n step))
    n))

(defn list-marker
  "The marker of a list item with `attrs` in the context `ctx`: its number
  in an ordered list, a dash in another list, and nothing outside a list."
  [attrs ctx]
  (case (:list ctx)
    :ol (str (list-number attrs ctx) ". ")
    :ul "- "
    ""))

(defn nested-list
  "The rendered `inner` of a list in the context `ctx`, when it's inside
  another list, or nil. The line breaks `before` it are one fewer when
  it's right inside the other list, after an item's line break."
  [inner before ctx]
  (when-let [nested (:nested ctx)]
    (if (= :item nested)
      (str before inner "\n")
      (str (subs before 1) (indented (whitespace/strip inner) nested true)
           "\n"))))

(defn text-inline
  "The plain text of the text `s` in the context `ctx`: its whitespace
  collapsed, but in a pre."
  [s ctx]
  (if (:pre ctx) s (whitespace/collapse s)))

(defn text-element
  "The plain text of the element of `tag` and `attrs`, whose children
  render as `inner`, in the context `ctx`."
  [[tag attrs] inner ctx]
  (let [href   (when (:links? ctx)
                 (some-> (url/checked (:url-fn ctx)
                                      (:allowed-schemes ctx)
                                      (:href attrs))
                         (str/replace #"[\t\n\r]" "")))
        shown? (and href (not= (whitespace/strip inner) href))]
    (cond
      (= :br tag)                  "\n"
      (= :hr tag)                  "\n\n"
      (= :img tag)                 (whitespace/collapse (str (:alt attrs)))
      (= :a tag)                   (if shown? (str inner " (" href ")") inner)
      (= :li tag)                  (let [marker (list-marker attrs ctx)]
                                     (str marker
                                          (indented (whitespace/strip inner)
                                                    (count marker)
                                                    false)
                                          "\n"))
      (and (#{:ul :ol} tag)
           (:nested ctx))          (nested-list inner "\n" ctx)
      ;; the tab after the last cell goes with the end of its line
      (#{:td :th} tag)             (str (whitespace/strip inner) "\t")
      (< 1 (:pre ctx 0))           inner
      ((:paragraph-tags ctx) tag)  (str "\n\n" inner "\n\n")
      ((:line-tags ctx) tag)       (str inner "\n")
      :else                        inner)))

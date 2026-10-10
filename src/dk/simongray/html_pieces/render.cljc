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
  preformatted text."
  [line]
  (if (str/starts-with? line pre-mark)
    line
    (whitespace/strip line)))

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
      (str/includes? text pre-mark) (str/replace pre-mark ""))))

(defn walk
  "The `nodes`, built Hiccup when `built?`, rendered as text by the
  functions `inline-fn` and `element-fn`, starting from the map `context`
  of options.

  The `inline-fn` takes a string and the context, and the `element-fn`
  takes a vector of the tag and attributes, the rendered children and the
  context. Inside an element, the context also has:

  - :list, :ul or :ol inside a list
  - :index, a volatile that counts the list items
  - :pre, :code and :quotes, how many pre, code and blockquote elements
    it's in, the element itself included"
  [nodes built? inline-fn element-fn context]
  (letfn [(node [x outer]
            (cond
              (string? x)
              (inline-fn x outer)

              ;; a tag of HTML can hold a dot or a #, e.g. <p.lead>, so only
              ;; Hiccup that's built has shorthand tags
              (and built? (tree/element? x) (tree/shorthand? (x 0)))
              (node (tree/expanded x) outer)

              (tree/element? x)
              (let [[tag attrs children] (tree/parts x)
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
                    (element-fn [tag attrs]
                                (if (and (= :pre tag) (not (:pre outer)))
                                  (marked-lines inner)
                                  inner)
                                ctx))))

              ;; e.g. of a for, spliced in as every Hiccup renderer does
              (seq? x)
              (str/join (into [] (map #(node % outer)) x))

              (number? x)
              (inline-fn (str x) outer)

              :else
              ""))]
    (tidy (str/join (into [] (map #(node % context)) nodes))
          (:quirks? context))))

(defn list-marker
  "The marker of a list item in the context `ctx`: its number in an ordered
  list, a dash in another list, and nothing outside a list."
  [{:keys [list index] :as ctx}]
  (case list
    :ol (str (vswap! index inc) ". ")
    :ul "- "
    ""))

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
                 (some-> (:href attrs) str (str/replace #"[\t\n\r]" "")))
        shown? (and href
                    (url/allowed? (:allowed-schemes ctx) href)
                    (not= (whitespace/strip inner) href))]
    (cond
      (= :br tag)                  "\n"
      (= :hr tag)                  "\n\n"
      (= :img tag)                 (whitespace/collapse (str (:alt attrs)))
      (= :a tag)                   (if shown? (str inner " (" href ")") inner)
      (= :li tag)                  (str (list-marker ctx)
                                        (whitespace/strip inner) "\n")
      (< 1 (:pre ctx 0))           inner
      ((:paragraph-tags ctx) tag)  (str "\n\n" inner "\n\n")
      ((:line-tags ctx) tag)       (str inner "\n")
      :else                        inner)))

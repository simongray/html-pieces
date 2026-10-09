(ns ^:no-doc dk.simongray.html-pieces.render
  "Hiccup rendered as text: a walk that renders each text and element by
  the functions that it's given, and the functions for plain text.

  The walk leaves out what a browser doesn't show and what a terminal
  would act on, and gives each line of preformatted text a mark, so that
  tidying the text leaves its indentation alone."
  (:require [clojure.string :as str]
            [dk.simongray.html-pieces.entities :as entities]
            [dk.simongray.html-pieces.tokenizer :as tokenizer]
            [dk.simongray.html-pieces.tree :as tree]
            [dk.simongray.html-pieces.url :as url]
            [dk.simongray.html-pieces.whitespace :as whitespace]))

(def pre-mark
  "The mark that walk puts at the start of each line of preformatted text,
  so that tidy leaves its indentation alone and then removes it. It's a
  character of the private use area, which no text holds."
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
  (let [trim-line #(if (str/starts-with? % pre-mark) % (whitespace/strip %))
        lines     (->> (str/split-lines (shown s quirks?))
                       (into [] (map trim-line))
                       (str/join "\n"))
        text      (whitespace/strip (cond-> lines
                                      (str/includes? lines "\n\n\n")
                                      (str/replace #"\n{3,}" "\n\n")))]
    (cond-> text
      (str/includes? text pre-mark) (str/replace pre-mark ""))))

(defn walk
  "The `nodes` rendered as text by the functions `inline-fn` and
  `element-fn`, starting from the map `context`.

  The `inline-fn` renders a string, and takes the string and the context.
  The `element-fn` renders an element, and takes its tag and attributes,
  its rendered children and the context. The context holds what these
  functions need, and walk reads two keys of it:

  - :dropped-tags, the elements that render as nothing, as a browser
    shows no script
  - :quirks?, as shown takes it

  For the elements inside, walk adds these keys:

  - :list is :ul or :ol inside a list
  - :index is a volatile that counts the list items
  - :pre, :code and :quotes count the pre, code and blockquote elements
    that it's in, the element itself included

  Every line of the outermost pre starts with pre-mark."
  [nodes inline-fn element-fn context]
  (letfn [(node [x outer]
            (cond
              (string? x)             (inline-fn x outer)
              (not (tree/element? x)) ""
              :else
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
                                ctx))))))]
    (tidy (str/join (into [] (map #(node % context)) nodes))
          (:quirks? context))))

(defn list-marker
  "The marker of a list item in the context `ctx` of walk: its number in an
  ordered list, a dash in another list, and nothing outside a list."
  [{:keys [list index] :as ctx}]
  (case list
    :ol (str (vswap! index inc) ". ")
    :ul "- "
    ""))

(defn text-inline
  "The plain text of the text `s` in the context `ctx` of walk: its
  whitespace collapsed, but in a pre."
  [s ctx]
  (if (:pre ctx) s (whitespace/collapse s)))

(defn text-element
  "The plain text of the element of `tag` and `attrs`, whose children
  render as `inner`, in the context `ctx` of walk. It reads these keys of
  the context:

  - :paragraph-tags, the elements set off by a blank line, and
    :line-tags, those that end a line
  - :links?, true to put the URL of a link after it, when its scheme is
    one of :allowed-schemes"
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

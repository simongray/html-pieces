(ns ^:no-doc dk.simongray.html-pieces.commonmark
  "Hiccup rendered as Markdown, by CommonMark 0.31.2, with the tables and
  strikethrough of GitHub Flavored Markdown."
  (:require [clojure.string :as str]
            [dk.simongray.html-pieces.render :as render]
            [dk.simongray.html-pieces.tokenizer :as tokenizer]
            [dk.simongray.html-pieces.url :as url]
            [dk.simongray.html-pieces.whitespace :as whitespace]))

;; CommonMark 0.31.2, section 2.4: a backslash escapes any ASCII
;; punctuation. These start inline markup, with the | of tables and the ~
;; of strikethrough in GitHub Flavored Markdown.
(def escapes
  "The characters that Markdown escapes in text."
  "\\`*_[]<>|~")

;; String.indexOf takes the character as a primitive int on the JVM
(defn char-in?
  "Whether the character at `i` of the text `s` is one of the `chars`."
  [chars s ^long i]
  #?(:clj  (<= 0 (.indexOf ^String chars (int (.charAt ^String s (int i)))))
     :cljs (<= 0 (.indexOf chars (.charAt s i)))))

;; Most text holds none of the characters, and is then given back as it is
(defn backslashed
  "The text `s` with a backslash before each of the `chars` in it."
  [s chars]
  (let [n (count s)]
    (loop [i 0 start 0 b nil]
      (cond
        (== i n)
        (if b
          (tokenizer/take-text! (tokenizer/append! b (subs s start n)))
          s)

        ;; the character itself starts the next run
        (char-in? chars s i)
        (let [b (or b (tokenizer/builder))]
          (-> b
              (tokenizer/append! (subs s start i))
              (tokenizer/append! "\\"))
          (recur (inc i) i b))

        :else
        (recur (inc i) start b)))))

;; CommonMark 0.31.2, section 2.5
(def reference-start
  "An ampersand that Markdown could read as the start of a character
  reference."
  #"&(?=#?[0-9A-Za-z]{1,32};)")

;; CommonMark 0.31.2, sections 4.1 to 4.3 and 5.2
(def block-start
  "What starts a heading, a thematic break or a list at the start of a
  line, after the space in front: a # + = or -, or the digits of an
  ordered list and the . or ) after them."
  #"^([\t\n\f\r ]*)(?:([#+=-])|([0-9]{1,9})([.)]))")

;; CommonMark 0.31.2, section 5.2: the digits of an ordered list and the
;; . or ) after them can come from two texts. A Latin-1 mark keeps strings
;; compact on the JVM.
(def digits-mark
  "The mark at the end of a text of digits alone, until the block around
  it is rendered. It's a C1 control that stands for no character of
  windows-1252, which rendered text leaves out."
  (str (char 0x81)))

(def digits-run
  "The characters that can come before a mark of digits on a line that
  they start: digits, spaces and marks."
  (str "0123456789 " digits-mark))

(defn escaped
  "The text `s` with the characters that start inline markup or a character
  reference escaped."
  [s]
  (cond-> (backslashed s escapes)
    (str/includes? s "&") (str/replace reference-start (constantly "\\&"))))

(defn escaped-text
  "The text `s` with what Markdown would read as markup escaped, including
  the start of a block."
  [s]
  (let [s     (escaped s)
        n     (count s)
        start (tokenizer/skip-whitespace s n 0)
        lead  (tokenizer/char-at s n start)]
    ;; any text can end up at the start of a line
    (cond
      (not (and lead (or (tokenizer/digit? lead) (#{\# \+ \= \-} lead))))
      s

      (== n (tokenizer/run-end s n start tokenizer/not-digit?))
      (str s digits-mark)

      :else
      (str/replace s
                   block-start
                   (fn [[_ space marker digits end]]
                     (if marker
                       (str space "\\" marker)
                       (str space digits "\\" end)))))))

;; CommonMark 0.31.2, section 6.3, without the tabs and line breaks that a
;; browser drops from a URL
(defn destination
  "The URL `s` as the destination of a Markdown link, escaped so that it
  can't end the link."
  [s]
  (-> (str/replace s #"[\t\n\r]" "")
      (str/replace " " "%20")
      (backslashed "\\()<>")
      (str/replace reference-start (constantly "\\&"))))

(defn backticks
  "The backticks around the code `s` in a span or a fence: one more than
  its longest run of them, and at least `n`."
  [s n]
  (let [longest (reduce max 0 (map count (re-seq #"`+" s)))]
    (apply str (repeat (max n (inc longest)) "`"))))

;; CommonMark 0.31.2, section 6.1: a span drops one space from each end
;; when both ends have one, and a backtick at an end would join the
;; backticks around it
(defn code-span
  "The code `s` as a Markdown code span."
  [s]
  (let [ticks (backticks s 1)
        pad   (if (or (str/starts-with? s "`")
                      (str/ends-with? s "`")
                      (and (str/starts-with? s " ") (str/ends-with? s " ")))
                " "
                "")]
    (str ticks pad s pad ticks)))

(def heading-levels
  "The level of each heading element."
  {:h1 1 :h2 2 :h3 3 :h4 4 :h5 5 :h6 6})

(def emphasis
  "The Markdown marks around the text of each element of emphasis."
  {:strong "**" :b "**" :em "*" :i "*" :s "~~" :strike "~~" :del "~~"})

;; CommonMark 0.31.2, section 2.1: the space separators of Unicode, and
;; the tab, line feed, form feed and carriage return
(defn unicode-space?
  "Whether the character `c` is Unicode whitespace."
  [c]
  (or (whitespace/char? c)
      (contains? #{\u00A0 \u1680 \u202F \u205F \u3000} c)
      (<= 0x2000 (tokenizer/code c) 0x200A)))

;; CommonMark 0.31.2, sections 6.2 and 6.7: emphasis doesn't start or end
;; at Unicode whitespace, and a hard line break at either end of a block
;; is a backslash
(defn emphasized
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

(defn digits-start?
  "Whether the digits before the index `i` of the Markdown `md` start a
  line or `md`, after the space in front."
  [md i]
  (loop [j (dec i)]
    (cond
      (neg? j)                   true
      (char-in? digits-run md j) (recur (dec j))
      :else                      (identical? \newline (nth md j)))))

;; Most blocks hold no mark, or one at the end, and are given back as they
;; are. The block around a mark at the end decides it.
(defn fix-digits
  "The Markdown `md` of a block with a backslash for each mark of digits
  that start a line or `md` before a . or ), and without the other marks
  but one at its end."
  [md]
  (let [n (count md)
        i (tokenizer/index-of md n digits-mark 0)]
    (if (<= (dec n) i)
      md
      (let [b (tokenizer/builder)]
        (loop [start 0 i i]
          (if (== n i)
            (tokenizer/take-text! (tokenizer/append! b (subs md start n)))
            (let [after (inc i)]
              (tokenizer/append! b (subs md start i))
              (cond
                (== n after)
                (tokenizer/append! b digits-mark)

                (and (char-in? ".)" md after) (digits-start? md i))
                (tokenizer/append! b "\\"))
              (recur after (tokenizer/index-of md n digits-mark after)))))))))

(defn block-text
  "The Markdown `inner` of a block with its marks of digits fixed, and
  without the line breaks and the whitespace at its ends."
  [inner]
  (whitespace/strip
   (cond-> (fix-digits inner)
     (str/includes? inner "\\\n")
     (-> (str/replace #"^(?:[\t\n\f\r ]*\\\n)+" "")
         (str/replace #"(?:\\\n[\t\n\f\r ]*)+$" "")))))

(defn inline
  "The Markdown of the text `s` in the context `ctx`: as it is in a pre,
  collapsed in a code element, and escaped elsewhere."
  [s ctx]
  (cond
    (:pre ctx)  s
    (:code ctx) (whitespace/collapse s)
    :else       (escaped-text (whitespace/collapse s))))

(defn element
  "The Markdown of the element of `tag` and `attrs`, whose children render
  as `inner`, in the context `ctx`."
  [[tag attrs] inner ctx]
  (let [heading (heading-levels tag)
        inside?(or (< (if (= :pre tag) 1 0) (:pre ctx 0))
                    (< (if (= :code tag) 1 0) (:code ctx 0)))
        safe?   #(and (string? %) (url/allowed? (:allowed-schemes ctx) %))
        quoted  #(->> (str/split-lines %)
                      (map (fn [line] (str "> " line)))
                      (str/join "\n"))]
    (cond
      inside?
      (case tag
        :br  "\n"
        :img (whitespace/collapse (str (:alt attrs)))
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
            alt (whitespace/collapse (str (:alt attrs)))]
        (if (safe? src)
          (str "![" (escaped alt) "](" (destination src) ")")
          (escaped-text alt)))

      (= :a tag)
      (let [href (:href attrs)]
        (if (and (safe? href) (not (whitespace/blank? inner)))
          (str "[" inner "](" (destination href) ")")
          inner))

      (emphasis tag)
      (emphasized (emphasis tag) inner)

      (= :code tag)
      (if (whitespace/blank? inner) inner (code-span inner))

      (= :pre tag)
      (let [code  (whitespace/strip inner)
            fence (backticks code 3)]
        (str "\n\n" fence "\n" code "\n" fence "\n\n"))

      (= :blockquote tag)
      (if (< (:max-quote-depth ctx) (:quotes ctx))
        (str "\n\n" inner "\n\n")
        (str "\n\n" (quoted (block-text inner)) "\n\n"))

      (= :li tag)
      (str (render/list-marker ctx) (block-text inner) "\n")

      (#{:td :th} tag)
      (str (block-text inner) " | ")

      (= :tr tag)
      (str "| " inner "\n")

      ((:paragraph-tags ctx) tag)
      (str "\n\n" (block-text inner) "\n\n")

      ((:line-tags ctx) tag)
      (str (block-text inner) "\n")

      ;; the nodes themselves, whose marks of digits are all decided here
      (nil? tag)
      (fix-digits inner)

      :else
      inner)))

(defn fix-breaks
  "The Markdown `md` with two line breaks in a row as the end of a
  paragraph, and without a line break before a block or at the end, which
  Markdown shows as a backslash."
  [md]
  ;; each regex runs only where it can match, since most Markdown has
  ;; neither case, and regexes are slow on the JVM
  (let [md (cond-> md
             (str/includes? md "\\\n\\\n")
             (str/replace #"(?<!\\)\\\n(?:\\\n)+" "\n\n"))]
    (cond-> md
      (or (str/includes? md "\\\n\n")
          (str/ends-with? md "\\")
          (str/ends-with? md "\\\n"))
      (str/replace #"(?<!\\)\\(?=\n\n|$)" ""))))

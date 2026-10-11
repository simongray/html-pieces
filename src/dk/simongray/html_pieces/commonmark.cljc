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

;; Rendered text leaves out these control characters, so the start of a
;; block or a character reference around them is escaped as if they
;; weren't there. With :quirks?, most C1 controls show as characters of
;; windows-1252 instead, and the escape is then needless but harmless.
(defn hidden?
  "Whether the character `c` is a control character that rendered text
  leaves out: any but a tab, a line feed or a carriage return."
  [c]
  ;; a primitive long, compared two numbers at a time, allocates nothing
  (let [x (long (tokenizer/code c))]
    (or (<= x 8)
        (== x 11)
        (== x 12)
        (and (<= 14 x) (<= x 31))
        (and (<= 127 x) (<= x 159)))))

(def hidden-chars
  "The hidden characters, as ranges of a character class of a regular
  expression."
  "\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F-\\x9F")

;; the predicates that end a run, made once rather than at each run

(def space-end?
  "Whether a character ends the space in front of a block: it's neither
  whitespace nor hidden."
  (complement #(or (whitespace/char? %) (hidden? %))))

(def digits-end?
  "Whether a character ends the digits of an ordered list: it's neither a
  digit nor hidden."
  (complement #(or (tokenizer/digit? %) (hidden? %))))

(def hidden-end?
  "Whether a character ends a run of hidden characters."
  (complement hidden?))

(def reference-end?
  "Whether a character ends what can come between the & and the ; of a
  character reference: it's neither a #, an ASCII alphanumeric nor hidden."
  (complement #(or (tokenizer/alphanumeric? %) (identical? \# %) (hidden? %))))

;; CommonMark 0.31.2, sections 4.1 to 4.3 and 5.2
(def block-start
  "What starts a heading, a thematic break or a list at the start of a
  line, after the space in front: a # + = or -, or the digits of an
  ordered list and the . or ) after them. Hidden characters can be in the
  space and after each digit."
  (re-pattern (str "^([\\t\\n\\r " hidden-chars "]*)"
                   "(?:([#+=-])|((?:[0-9][" hidden-chars "]*){1,9})([.)]))")))

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
  they start: digits, spaces and hidden characters, marks among them."
  (apply str "0123456789 " (filter hidden? (map char (range 160)))))

;; CommonMark 0.31.2, section 2.5. Each hidden run belongs to the character
;; before it, so that a long one is matched in linear time.
(def reference-start
  "An ampersand that Markdown could read as the start of a character
  reference. Hidden characters can come after it and each character of
  the reference but the ;."
  (re-pattern (str "&(?=[" hidden-chars "]*(?:#[" hidden-chars "]*)?"
                   "(?:[0-9A-Za-z][" hidden-chars "]*){1,32};)")))

;; CommonMark 0.31.2, section 2.5: a character reference can come from two
;; texts or more
(def reference-mark
  "The mark before an ampersand that the texts after it could make the
  start of a character reference, until the block around it is rendered.
  It's a C1 control that stands for no character of windows-1252, which
  rendered text leaves out."
  (str (char 0x8D)))

(defn escaped
  "The text `s` with the characters that start inline markup or a character
  reference escaped."
  [s]
  (cond-> (backslashed s escapes)
    (str/includes? s "&") (str/replace reference-start (constantly "\\&"))))

;; A reference holds no other ampersand, so only the last one can be open,
;; and only while no more than a #, alphanumerics and hidden characters
;; come after it
(defn reference-marked
  "The escaped text `s` with a mark before an ampersand that the texts
  after it could make the start of a character reference."
  [s]
  (let [n (count s)
        i (str/last-index-of s "&")]
    (if (and i (== n (tokenizer/run-end s n (inc i) reference-end?)))
      (str (subs s 0 i) reference-mark (subs s i))
      s)))

(defn escaped-text
  "The text `s` with what Markdown would read as markup escaped, including
  the start of a block."
  [s]
  (let [s     (reference-marked (escaped s))
        n     (count s)
        start (tokenizer/run-end s n 0 space-end?)
        lead  (tokenizer/char-at s n start)]
    ;; any text can end up at the start of a line
    (cond
      (not (and lead (or (tokenizer/digit? lead) (#{\# \+ \= \-} lead))))
      s

      (== n (tokenizer/run-end s n start digits-end?))
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
  "The Markdown marks around the text of each kind of emphasis."
  {:strong "**" :em "*" :s "~~"})

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

;; the code units of * and ~, which the JVM compares without boxing
(defn mark-at?
  "Whether the character at `i` of the text `s` is a mark of emphasis."
  [s ^long i]
  (let [x (tokenizer/code-at s i)]
    (or (== 42 x) (== 126 x))))

(defn marks-meet?
  "Whether the Markdown `l` ends with a mark of emphasis and `r` starts
  with one, neither of them empty."
  [l r]
  (and (mark-at? l (dec (count l))) (mark-at? r 0)))

;; CommonMark 0.31.2, section 2.4: an odd number of backslashes escapes
;; the mark after them, which is then text
(def closing-marks
  "The marks of emphasis at the end of Markdown, after the backslashes
  before them."
  #"(\\*)([*~]+)$")

(def mark-runs
  "A run of the same mark of emphasis."
  #"\*+|~+")

;; CommonMark 0.31.2, section 6.2: delimiter runs next to each other join,
;; e.g. *a**b* is one emphasis around a**b. A run of three asterisks closes
;; or opens emphasis and strong emphasis in an order that it doesn't tell,
;; so it's left as it is.
(defn shared-marks
  "The number of characters of marks where emphasis closes at the end of
  the Markdown `l` and the same emphasis opens at the start of `r`: those
  of each run of marks that is the same in both, from where they meet."
  [l r]
  (let [[_ slashes closing] (re-find closing-marks l)
        closing             (cond-> closing (odd? (count slashes)) (subs 1))
        opening             (re-find #"^[*~]+" r)]
    (->> (map vector
              (reverse (re-seq mark-runs closing))
              (re-seq mark-runs opening))
         (take-while (fn [[a b]] (and (= a b) (<= (count a) 2))))
         (map (comp count first))
         (reduce + 0))))

(defn joined-pair
  "The Markdown `l` and `r` of nodes next to each other, without the marks
  where emphasis closes at the end of `l` and opens again at the start of
  `r`."
  [l r]
  (let [n (shared-marks l r)]
    [(subs l 0 (- (count l) n)) (subs r n)]))

(defn rejoined
  "The Markdown `xs` of nodes next to each other, without those that are
  empty and the marks where emphasis closes and opens again between them."
  [xs]
  (reduce (fn [acc r]
            (let [l (peek acc)]
              (cond
                (= "" r)                  acc
                (and l (marks-meet? l r)) (into (pop acc) (joined-pair l r))
                :else                     (conj acc r))))
          []
          xs))

(defn text-length
  "The number of characters in the texts `xs`."
  ^long [xs]
  (let [n (count xs)]
    (loop [i 0 length 0]
      (if (== i n)
        length
        (recur (inc i) (+ length (count (nth xs i))))))))

;; a builder with room for all of them, which the JVM then never grows,
;; while JavaScript's has no room to set
(defn joined-as-is
  "The Markdown `xs` of nodes next to each other as one, or nil when marks
  of emphasis meet between them."
  [xs]
  (let [n (count xs)
        b #?(:clj  (tokenizer/builder (text-length xs))
             :cljs (tokenizer/builder))]
    (loop [i 0 after-mark? false]
      (if (== i n)
        (or (tokenizer/take-text! b) "")
        (let [s (nth xs i)
              k (count s)]
          (cond
            (zero? k)                        (recur (inc i) after-mark?)
            (and after-mark? (mark-at? s 0)) nil
            :else                            (do (tokenizer/append! b s)
                                                 (recur (inc i)
                                                        (mark-at? s (dec k))))))))))

;; one node is given back as it is, and most are joined in one pass, since
;; marks seldom meet
(defn joined
  "The Markdown `xs` of nodes next to each other as one, where emphasis
  that closes and opens again is one, e.g. *ab* rather than *a**b*."
  [xs]
  (if (< (count xs) 2)
    (nth xs 0 "")
    (or (joined-as-is xs) (str/join (rejoined xs)))))

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
            ;; the character after the mark is past any hidden characters,
            ;; and the last of the marks in front of it decides
            (let [after (tokenizer/run-end md n (inc i) hidden-end?)
                  j     (tokenizer/index-of md n digits-mark (inc i))]
              (tokenizer/append! b (subs md start i))
              (cond
                (< j after)
                nil

                (== n after)
                (tokenizer/append! b digits-mark)

                (and (char-in? ".)" md after) (digits-start? md i))
                (tokenizer/append! b "\\"))
              (recur (inc i) j))))))))

;; the regex sees no further than a reference could reach, up to its ;
(defn reference-at?
  "Whether the Markdown `md` of length `n` has an ampersand at `i` that
  starts a character reference."
  [md n i]
  (and (identical? \& (tokenizer/char-at md n i))
       (let [end (tokenizer/run-end md n (inc i) reference-end?)]
         (some? (re-find reference-start (subs md i (min n (inc end))))))))

;; Most blocks hold no mark, and are given back as they are. A reference
;; can't go on past the end of a block, so the block decides every mark.
(defn fix-references
  "The Markdown `md` of a block with a backslash for each mark of an
  ampersand that starts a character reference, and without the other
  marks."
  [md]
  (let [n (count md)
        i (tokenizer/index-of md n reference-mark 0)]
    (if (== n i)
      md
      (let [b (tokenizer/builder)]
        (loop [start 0 i i]
          (if (== n i)
            (or (tokenizer/take-text! (tokenizer/append! b (subs md start n)))
                "")
            ;; the ampersand is past any hidden characters, and the last of
            ;; the marks in front of it decides
            (let [after (tokenizer/run-end md n (inc i) hidden-end?)
                  j     (tokenizer/index-of md n reference-mark (inc i))]
              (tokenizer/append! b (subs md start i))
              (when (and (< after j) (reference-at? md n after))
                (tokenizer/append! b "\\"))
              (recur (inc i) j))))))))

(defn block-text
  "The Markdown `inner` of a block with its marks fixed, and without the
  line breaks and the whitespace at its ends."
  [inner]
  (whitespace/strip
   (cond-> (fix-references (fix-digits inner))
     (str/includes? inner "\\\n")
     (-> (str/replace #"^(?:[\t\n\f\r ]*\\\n)+" "")
         (str/replace #"(?:\\\n[\t\n\f\r ]*)+$" "")))))

;; CommonMark 0.31.2, section 4.2: an ATX heading is one line, and the #
;; at its end after a space or tab are a closing sequence, not text
(defn heading-line
  "The Markdown `inner` as an ATX heading of `level`, with each line break
  and the spaces around it as one space, and a backslash before the # that
  would close it."
  [level inner]
  (let [text (block-text inner)
        line (str (apply str (repeat level "#")) " "
                  (cond-> text
                    ;; a hard line break is a backslash before the line break
                    (str/includes? text "\n")
                    (str/replace #"[\t ]*(?:\\?\n[\t ]*)+" " ")))]
    (cond-> line
      (str/ends-with? line "#")
      (str/replace #"(?<=[\t ])#+$" #(str "\\" %)))))

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
        checked #(url/checked (:url-fn ctx) (:allowed-schemes ctx) %)
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
      (str "\n\n" (heading-line heading inner) "\n\n")

      (= :br tag)
      "\\\n"

      (= :hr tag)
      "\n\n---\n\n"

      (= :img tag)
      (let [src (checked (:src attrs))
            alt (whitespace/collapse (str (:alt attrs)))]
        (if src
          (str "![" (escaped alt) "](" (destination src) ")")
          (escaped-text alt)))

      (= :a tag)
      (let [href (checked (:href attrs))]
        (if (and href (not (whitespace/blank? inner)))
          (str "[" inner "](" (destination href) ")")
          inner))

      ;; emphasis inside emphasis of its kind looks no different, and its
      ;; marks would join those around it, e.g. an em in an em as **a**
      (render/emphasis-kinds tag)
      (let [kind (render/emphasis-kinds tag)]
        (if (< 1 (kind ctx))
          inner
          (emphasized (emphasis kind) inner)))

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

      ;; the nodes themselves, whose marks are all decided here
      (nil? tag)
      (fix-references (fix-digits inner))

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

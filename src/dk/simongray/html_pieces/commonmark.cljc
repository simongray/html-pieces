(ns ^:no-doc dk.simongray.html-pieces.commonmark
  "Hiccup rendered as Markdown, by CommonMark 0.31.2, with the tables and
  strikethrough of GitHub Flavored Markdown.

  Text is escaped wherever Markdown would read it as markup, so that a
  Markdown renderer shows it as it is. A link or an image keeps its URL
  only when the scheme is allowed, and the URL is escaped so that it can't
  end the link."
  (:require [clojure.string :as str]
            [dk.simongray.html-pieces.render :as render]
            [dk.simongray.html-pieces.tokenizer :as tokenizer]
            [dk.simongray.html-pieces.url :as url]
            [dk.simongray.html-pieces.whitespace :as whitespace]))

;; CommonMark 0.31.2, section 2.4: a backslash escapes any ASCII
;; punctuation. These start inline markup, with the | of tables and the ~
;; of strikethrough in GitHub Flavored Markdown.
(def escapes
  "The characters that Markdown escapes in text, by character."
  {\\ "\\\\" \` "\\`" \* "\\*" \_ "\\_" \[ "\\[" \] "\\]" \< "\\<" \> "\\>"
   \| "\\|" \~ "\\~"})

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

(defn escaped
  "The text `s` with the characters of escapes and the start of a character
  reference escaped, so that Markdown shows it as it is."
  [s]
  (cond-> (str/escape s escapes)
    (str/includes? s "&") (str/replace reference-start (constantly "\\&"))))

(defn escaped-text
  "The text `s` as escaped gives it, and with what would start a block at
  the start of a line escaped too, since any text can start one."
  [s]
  (let [s    (escaped s)
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
(defn destination
  "The URL `s` as the destination of a Markdown link: without the tabs and
  line breaks that a browser drops, with its spaces encoded, and with what
  would end it or start an escape or a reference escaped."
  [s]
  (-> (str/replace s #"[\t\n\r]" "")
      (str/replace " " "%20")
      (str/escape {\\ "\\\\" \( "\\(" \) "\\)" \< "\\<" \> "\\>"})
      (str/replace reference-start (constantly "\\&"))))

(defn backticks
  "The backticks around the code `s` in a span or a fence: one more than
  its longest run of them, and at least `n`."
  [s n]
  (let [longest (reduce max 0 (map count (re-seq #"`+" s)))]
    (apply str (repeat (max n (inc longest)) "`"))))

;; CommonMark 0.31.2, section 6.1: a span drops one space from each end
;; when both ends have one
(defn code-span
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

(defn block-text
  "The Markdown `inner` of a block without the line breaks and the
  whitespace at its ends."
  [inner]
  (whitespace/strip
   (cond-> inner
     (str/includes? inner "\\\n")
     (-> (str/replace #"^(?:[\t\n\f\r ]*\\\n)+" "")
         (str/replace #"(?:\\\n[\t\n\f\r ]*)+$" "")))))

(defn inline
  "The Markdown of the text `s` in the context `ctx` of a walk of the
  render namespace: as it is in a pre, collapsed in a code element, and
  escaped elsewhere."
  [s ctx]
  (cond
    (:pre ctx)  s
    (:code ctx) (whitespace/collapse s)
    :else       (escaped-text (whitespace/collapse s))))

(defn element
  "The Markdown of the element of `tag` and `attrs`, whose children render
  as `inner`, in the context `ctx` of a walk of the render namespace. An
  element inside a pre or a code element is its text. It reads these keys
  of the context:

  - :paragraph-tags, the elements set off by a blank line, and
    :line-tags, those that end a line
  - :allowed-schemes, the schemes of the URLs that a link or an image
    keeps, which is otherwise its text
  - :max-quotes, how deeply quotes nest, past which a quote is a
    paragraph"
  [[tag attrs] inner ctx]
  (let [heading (when-let [[_ n] (re-matches #"h([1-6])" (name tag))]
                  (parse-long n))
        inside? (or (< (if (= :pre tag) 1 0) (:pre ctx 0))
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
            alt (escaped (whitespace/collapse (str (:alt attrs))))]
        (if (safe? src)
          (str "![" alt "](" (destination src) ")")
          alt))

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
      (if (< (:max-quotes ctx) (:quotes ctx))
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

      :else
      inner)))

(defn with-breaks-fixed
  "The Markdown `md` with two line breaks in a row, which Markdown has no
  way to write, as the end of a paragraph, and without a line break before
  a block or at the end, which Markdown shows as a backslash."
  [md]
  (cond-> md
    (str/includes? md "\\")
    (-> (str/replace #"(?<!\\)\\\n(?:\\\n)+" "\n\n")
        (str/replace #"(?<!\\)\\(?=\n\n|$)" ""))))

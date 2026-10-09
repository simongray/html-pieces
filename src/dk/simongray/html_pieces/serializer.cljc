(ns ^:no-doc dk.simongray.html-pieces.serializer
  "Hiccup written as HTML, as 13.3 of the HTML Standard serializes a
  fragment. Every writer appends to a text builder and gives it back."
  (:require [dk.simongray.html-pieces.tokenizer :as tokenizer]
            [dk.simongray.html-pieces.tree :as tree]))

;; Characters are matched by their code, since case hashes a character on
;; the JVM

;; HTML Standard, 13.1.2.3, attribute names, whose rule holds the names
;; of elements to it as well
(defn name-code?
  "Whether the character of the code `c` can be part of the name of an
  element or an attribute: anything but a control, a space, \", ', /, =
  or >."
  [^long c]
  (case c
    (34 39 47 61 62) false
    (not (or (<= c 0x20)
             (and (<= 0x7F c) (<= c 0x9F))))))

(defn every-code?
  "Whether `pred` is true of the code of every character of the text `s`."
  [pred s]
  (let [n (count s)]
    (loop [i 0]
      (or (== i n)
          (and (pred (tokenizer/code-at s i))
               (recur (inc i)))))))

(defn html-name
  "The name of the keyword or string `k` when HTML can write it as the
  name of an element or an attribute, or else nil."
  [k]
  (when (or (keyword? k) (string? k))
    (let [s (name k)]
      (when (and (seq s) (every-code? name-code? s))
        s))))

(defn escape
  "The escape of the character of the code `c`, in an attribute value when
  `attr?`, or nil when it needs none."
  [^long c attr?]
  (case c
    38 "&amp;"
    60 "&lt;"
    62 "&gt;"
    34 (when attr? "&quot;")
    nil))

;; Most text needs no escape, and is then appended whole
(defn escaped!
  "Append the text `s` to the builder `b`, with & < and > escaped, and \"
  too when `attr?`."
  [b s attr?]
  (let [n (count s)]
    (loop [i 0 start 0]
      (if (== i n)
        (tokenizer/append! b (subs s start n))
        (if-let [e (escape (tokenizer/code-at s i) attr?)]
          (do (-> b
                  (tokenizer/append! (subs s start i))
                  (tokenizer/append! e))
              (recur (inc i) (inc i)))
          (recur (inc i) start))))))

(defn attribute!
  "Append the attribute of the name `n` and the value `v` to the builder
  `b`: the name alone when `v` is true."
  [b n v]
  (cond-> (-> b
              (tokenizer/append! " ")
              (tokenizer/append! n))
    (not (true? v)) (-> (tokenizer/append! "=\"")
                        (escaped! (str v) true)
                        (tokenizer/append! "\""))))

(defn attributes!
  "Append the attributes `attrs` to the builder `b`, without those whose
  name HTML can't hold, or whose value is false or nil."
  [b attrs]
  (reduce-kv (fn [b k v]
               (let [n (html-name k)]
                 (cond-> b
                   (and n (some? v) (not (false? v))) (attribute! n v))))
             b
             attrs))

(defn start-tag!
  "Append the start tag of the element named `n`, with its `attrs`, to the
  builder `b`."
  [b n attrs]
  (-> b
      (tokenizer/append! "<")
      (tokenizer/append! n)
      (attributes! attrs)
      (tokenizer/append! ">")))

(defn end-tag!
  "Append the end tag of the element named `n` to the builder `b`."
  [b n]
  (-> b
      (tokenizer/append! "</")
      (tokenizer/append! n)
      (tokenizer/append! ">")))

;; One builder for the whole tree, rather than a string for each element
;; that every parent copies again
(defn html
  "The Hiccup `nodes` as an HTML string. An attribute whose name HTML can't
  hold is left out, and such an element is replaced by its children."
  [nodes]
  (let [b (tokenizer/builder)]
    (letfn [(node [x]
              (cond
                (string? x)       (escaped! b x false)
                (tree/element? x) (element x)))
            (element [x]
              (let [[tag attrs children] (tree/parts x)]
                (if-let [n (html-name tag)]
                  (do (start-tag! b n attrs)
                      (when-not (tree/void-elements tag)
                        (run! node children)
                        (end-tag! b n)))
                  (run! node children))))]
      (run! node nodes)
      (or (tokenizer/take-text! b) ""))))

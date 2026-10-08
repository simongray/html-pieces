(ns ^:no-doc dk.simongray.html-pieces.serializer
  "Hiccup written as HTML, as 13.3 of the HTML Standard serializes a
  fragment: the text escaped, and the void elements without end tags."
  (:require [clojure.string :as str]
            [dk.simongray.html-pieces.tree :as tree]))

(def text-escapes
  "The characters that text in HTML escapes, by character."
  {\& "&amp;" \< "&lt;" \> "&gt;"})

(def value-escapes
  "The characters that an attribute value in double quotes escapes, by
  character."
  (assoc text-escapes \" "&quot;"))

;; HTML Standard, 13.1.2.3, attribute names, whose rule holds the names
;; of elements to it as well
(defn html-name
  "The name of the keyword or string `k` when HTML can write it as the
  name of an element or an attribute, or else nil."
  [k]
  (when (or (keyword? k) (string? k))
    (let [s (name k)]
      (when-not (or (= "" s) (re-find #"[\x00-\x20\x7F-\x9F\"'/=>]" s))
        s))))

(defn attributes
  "The attributes `attrs` as HTML, without those whose name HTML can't
  hold, or whose value is false or nil."
  [attrs]
  (str/join (for [[k v] attrs
                  :let  [n (html-name k)]
                  :when (and n (some? v) (not (false? v)))]
              (if (true? v)
                (str " " n)
                (str " " n "=\"" (str/escape (str v) value-escapes) "\"")))))

(defn html
  "The Hiccup `nodes` as an HTML string. An attribute whose name HTML can't
  hold is left out, and such an element is replaced by its children."
  [nodes]
  (letfn [(node [x]
            (cond
              (string? x)             (str/escape x text-escapes)
              (not (tree/element? x)) ""
              :else
              (let [[tag attrs children] (tree/parts x)
                    inner #(str/join (map node children))]
                (if-let [name (html-name tag)]
                  (let [start (str "<" name (attributes attrs) ">")]
                    (if (tree/void-elements tag)
                      start
                      (str start (inner) "</" name ">")))
                  (inner)))))]
    (str/join (map node nodes))))

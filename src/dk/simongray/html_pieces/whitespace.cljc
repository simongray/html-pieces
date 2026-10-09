(ns ^:no-doc dk.simongray.html-pieces.whitespace
  "ASCII whitespace, as the Infra Standard defines it: the tab, line feed,
  form feed, carriage return and space.

  HTML collapses and strips no other whitespace, so a no-break space
  stays. The \\s of regular expressions and str/trim differ on it between
  the JVM and JavaScript, so neither is used for text."
  (:refer-clojure :exclude [char?])
  (:require [clojure.string :as str]
            [dk.simongray.html-pieces.tokenizer :as tokenizer]))

(defn char?
  "Whether the character `c` is ASCII whitespace."
  [c]
  (case c
    (\tab \newline \formfeed \return \space) true
    false))

(defn strip
  "The text `s` without the ASCII whitespace at its ends."
  [s]
  (let [n     (count s)
        end   (loop [i n]
                (if (and (pos? i) (char? (tokenizer/char-at s n (dec i))))
                  (recur (dec i))
                  i))
        start (loop [i 0]
                (if (and (< i end) (char? (tokenizer/char-at s n i)))
                  (recur (inc i))
                  i))]
    (if (and (zero? start) (= n end))
      s
      (subs s start end))))

(defn blank?
  "Whether the text `s` holds nothing but ASCII whitespace."
  [s]
  (let [n (count s)]
    (loop [i 0]
      (cond
        (= n i)                             true
        (char? (tokenizer/char-at s n i))   (recur (inc i))
        :else                               false))))

(defn collapse
  "The text `s` with each run of ASCII whitespace as one space, but not
  stripped."
  [s]
  ;; most text has only single spaces, and then the regex is skipped
  (if (some #(str/includes? s %) ["\n" "  " "\t" "\r" "\f"])
    (str/replace s #"[\t\n\f\r ]+" " ")
    s))

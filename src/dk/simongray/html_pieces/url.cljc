(ns dk.simongray.html-pieces.url
  "URLs resolved against a base URL as a browser resolves them, and checked
  against a set of allowed schemes.

  A URL is resolved by whatwg-url, which implements the URL Standard,
  hosts and IDNA included."
  (:refer-clojure :exclude [resolve])
  (:require [clojure.string :as str]
            [dk.simongray.html-pieces.tokenizer :as tokenizer]
            [dk.simongray.html-pieces.whitespace :as whitespace]
            [dk.simongray.whatwg-url :as whatwg-url]))

(defn resolver
  "A function of a URL that resolves it against the absolute URL `base`, as
  resolve does, but reads the base only once."
  [base]
  (let [b (some-> base whatwg-url/parse)]
    (fn [s]
      (when (string? s)
        (whatwg-url/resolve b s)))))

(defn resolve
  "The URL `s` resolved against the absolute URL `base`, and normalized as
  a browser normalizes it, or nil when it isn't valid. With no `base`, only
  an absolute URL is valid.

      (resolve \"http://a/b/c/d;p?q\" \"../g\")
      ;; => \"http://a/b/g\""
  [base s]
  ((resolver base) s))

;; URL Standard, "Concepts": a URL is read without its tabs and line breaks,
;; and without the C0 controls and spaces at its ends
(defn- cleaned
  "The URL `s` as the URL Standard reads it."
  [s]
  (if (or (= "" s)
          (<= (tokenizer/code-at s 0) 0x20)
          (<= (tokenizer/code-at s (dec (count s))) 0x20)
          (str/includes? s "\t")
          (str/includes? s "\n")
          (str/includes? s "\r"))
    (-> s
        (str/replace #"^[\x00-\x20]+|[\x00-\x20]+$" "")
        (str/replace #"[\t\n\r]" ""))
    s))

(defn- alpha-code?
  "Whether the code `c` is an ASCII letter."
  [^long c]
  (or (and (<= 0x41 c) (<= c 0x5A))
      (and (<= 0x61 c) (<= c 0x7A))))

(defn- scheme-code?
  "Whether the code `c` can be in a scheme after its first letter."
  [^long c]
  (or (alpha-code? c)
      (and (<= 0x30 c) (<= c 0x39))
      (== c 0x2B) (== c 0x2D) (== c 0x2E)))

;; str/lower-case follows the default locale on the JVM, which in Turkish
;; makes I a dotless i
(defn- lower-case
  "The text `s` in lower case, whatever the locale."
  [s]
  #?(:clj  (.toLowerCase ^String s java.util.Locale/ROOT)
     :cljs (.toLowerCase s)))

(defn- split-scheme
  "The scheme of the URL `s` in lower case and the rest of it, or nil and
  `s` when it has none."
  [s]
  (let [n     (count s)
        colon (when (and (pos? n) (alpha-code? (tokenizer/code-at s 0)))
                (loop [i 1]
                  (when (< i n)
                    (let [c (tokenizer/code-at s i)]
                      (cond
                        (== 0x3A c)       i
                        (scheme-code? c)  (recur (inc i))
                        :else             nil)))))]
    (if colon
      [(lower-case (subs s 0 colon)) (subs s (inc colon))]
      [nil s])))

(defn ^:no-doc scheme
  "The scheme of the URL `s` in lower case, or nil for a relative URL. The
  URL is read as a browser reads it, so e.g. a tab inside javascript:
  doesn't hide the scheme."
  [s]
  (first (split-scheme (cleaned (str s)))))

;; a relative URL is never allowed, since a browser would resolve it
;; against its own page rather than the page that the HTML came from
(defn ^:no-doc allowed?
  "Whether the URL `s` is absolute, with a scheme of `schemes`, read as a
  browser reads it."
  [schemes s]
  (contains? schemes (scheme s)))

(defn ^:no-doc checked
  "The URL `s` as the function `rewrite` gives it, when that's a URL with
  one of `schemes`, or else nil."
  [rewrite schemes s]
  (when (string? s)
    (let [u (rewrite s)]
      (when (and (string? u) (allowed? schemes u))
        u))))

(defn ^:no-doc checked-list
  "The list of URLs `s`, each one as `rewrite` gives it, or nil when one
  hasn't a scheme of `schemes`. With `srcset?`, the URLs are separated by
  commas, each with a descriptor after it, and otherwise by spaces."
  [rewrite schemes srcset? s]
  (when (string? s)
    (let [items     (->> (str/split s (if srcset? #"," #"[\t\n\f\r ]+"))
                         (map whitespace/strip)
                         (remove whitespace/blank?))
          candidate #"([^\t\n\f\r ]+)(.*)"
          urls      (for [item items
                          :let [[_ u more] (re-matches candidate item)]]
                      (some-> (checked rewrite schemes u) (str more)))]
      (when (and (seq urls) (every? some? urls))
        (str/join (if srcset? ", " " ") urls)))))

(comment
  (resolve "http://example.com" "bios/mitchell-baker/")
  (resolve "http://a/b/c/d;p?q" "http:g")
  (resolve "http://example.com/blog/" "HTTP://Example.COM:80/a/./b/../c d")
  (resolve "mailto:jane@example.com" "#top")
  (resolve nil "http://0x7f.1/")
  (scheme " java\tscript:alert(1)")
  #_.)

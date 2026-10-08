(ns ^:no-doc dk.simongray.html-pieces.url
  "URLs read as a browser reads them, by the basic URL parser of the URL
  Standard, and checked against a set of allowed schemes."
  (:require [clojure.string :as str]
            [dk.simongray.html-pieces.whitespace :as whitespace]))

(defn scheme
  "The scheme of the URL `s` in lower case, or nil for a relative URL.

  The URL is read as a browser reads it, so a tab inside javascript:
  doesn't hide the scheme. The tabs and line breaks anywhere in it are
  dropped, and so are the control characters and spaces in front."
  [s]
  (let [s (-> (str s)
              (str/replace #"[\t\n\r]" "")
              (str/replace #"^[\x00-\x20]+" ""))]
    (some-> (re-find #"^[a-zA-Z][a-zA-Z0-9+.-]*(?=:)" s) str/lower-case)))

(defn allowed?
  "Whether the URL `s` is absolute, with a scheme of `schemes`, read as a
  browser reads it."
  [schemes s]
  (contains? schemes (scheme s)))

(defn checked
  "The URL `s` as the function `rewrite` gives it, when that's a URL with
  one of `schemes`, or else nil."
  [rewrite schemes s]
  (when (string? s)
    (let [u (rewrite s)]
      (when (and (string? u) (allowed? schemes u))
        u))))

(defn checked-list
  "The list of URLs `s`, each one as checked gives it with `rewrite` and
  `schemes`, or nil when one isn't allowed. With `srcset?`, the URLs are
  separated by commas, with a descriptor after each, as in a srcset, and
  otherwise by spaces."
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

(ns dk.simongray.html-pieces.url-test
  "URLs resolved against a base URL as a browser resolves them."
  (:require [clojure.test :refer [deftest is]]
            [dk.simongray.html-pieces.url :as url]))

;; RFC 3986, 5.4, but for the two references that the URL Standard reads
;; otherwise: //g gets the path /, and http:g is relative
(def rfc-3986-examples
  {"g:h"           "g:h"
   "g"             "http://a/b/c/g"
   "./g"           "http://a/b/c/g"
   "g/"            "http://a/b/c/g/"
   "/g"            "http://a/g"
   "//g"           "http://g/"
   "?y"            "http://a/b/c/d;p?y"
   "g?y"           "http://a/b/c/g?y"
   "#s"            "http://a/b/c/d;p?q#s"
   "g#s"           "http://a/b/c/g#s"
   "g?y#s"         "http://a/b/c/g?y#s"
   ";x"            "http://a/b/c/;x"
   "g;x"           "http://a/b/c/g;x"
   "g;x?y#s"       "http://a/b/c/g;x?y#s"
   ""              "http://a/b/c/d;p?q"
   "."             "http://a/b/c/"
   "./"            "http://a/b/c/"
   ".."            "http://a/b/"
   "../"           "http://a/b/"
   "../g"          "http://a/b/g"
   "../.."         "http://a/"
   "../../"        "http://a/"
   "../../g"       "http://a/g"
   "../../../g"    "http://a/g"
   "../../../../g" "http://a/g"
   "/./g"          "http://a/g"
   "/../g"         "http://a/g"
   "g."            "http://a/b/c/g."
   ".g"            "http://a/b/c/.g"
   "g.."           "http://a/b/c/g.."
   "..g"           "http://a/b/c/..g"
   "./../g"        "http://a/b/g"
   "./g/."         "http://a/b/c/g/"
   "g/./h"         "http://a/b/c/g/h"
   "g/../h"        "http://a/b/c/h"
   "g;x=1/./y"     "http://a/b/c/g;x=1/y"
   "g;x=1/../y"    "http://a/b/c/y"
   "g?y/./x"       "http://a/b/c/g?y/./x"
   "g?y/../x"      "http://a/b/c/g?y/../x"
   "g#s/./x"       "http://a/b/c/g#s/./x"
   "g#s/../x"      "http://a/b/c/g#s/../x"
   "http:g"        "http://a/b/c/g"})

(deftest the-examples-of-rfc-3986
  (doseq [[ref expected] rfc-3986-examples]
    (is (= expected (url/resolve "http://a/b/c/d;p?q" ref)) ref)))

(deftest what-the-url-standard-changes
  (is (= "http://example.com/" (url/resolve "http://example.com" "")))
  (is (= "http://example.com/a/c%20d"
         (url/resolve "http://example.com/blog/" "HTTP://Example.COM:80/a/./b/../c d")))
  (is (= "http://a/ab" (url/resolve "http://a/b" " \t/a\nb ")))
  (is (= "http://x/y" (url/resolve "http://a/b" "\\\\x\\y")))
  (is (= "https://x.org/p" (url/resolve "http://a/b" "https:x.org/p")))
  (is (= "http://x/y" (url/resolve "http://a/b" "///x/y")))
  (is (= "http://a/c" (url/resolve "http://a/b" "%2e%2E/c")))
  (is (= "http://a/b/c?q=a%20b%27c" (url/resolve "http://a/b/c" "?q=a b'c")))
  (is (= "http://a/%C3%A9?%C3%A9#%C3%A9" (url/resolve "http://a/b" "é?é#é")))
  (is (= "http://a/%F0%9F%98%80" (url/resolve "http://a" "😀")))
  (is (= "http://u:p@x/" (url/resolve "http://a/b" "http://u:p@x:0080/")))
  (is (= "foo://h/r" (url/resolve "foo://h/p/q" "../r"))))

#?(:clj
   (deftest whatever-the-locale
     (let [default (java.util.Locale/getDefault)]
       (try
         (java.util.Locale/setDefault (java.util.Locale/forLanguageTag "tr"))
         (is (= "https://wiki.example/" (url/resolve nil "HTTPS://WIKI.EXAMPLE")))
         (finally
           (java.util.Locale/setDefault default))))))

(deftest opaque-and-invalid-urls
  (is (= "mailto:jane@example.com#top" (url/resolve "mailto:jane@example.com" "#top")))
  (is (nil? (url/resolve "mailto:jane@example.com" "x")))
  (is (= "javascript:alert(1)" (url/resolve "http://a/b" "javascript:alert(1)")))
  (is (nil? (url/resolve "http://a/b" "http://")))
  (is (nil? (url/resolve "http://a/b" "http://x:y/")))
  (is (nil? (url/resolve nil "relative")))
  (is (= "https://x.y/" (url/resolve nil "HTTPS://X.y"))))

(deftest hosts-as-a-browser-reads-them
  (is (= "http://127.0.0.1/" (url/resolve nil "http://0x7f.1/")))
  (is (= "http://xn--bcher-kva.example/" (url/resolve nil "http://Bücher.example/")))
  (is (= "http://[::1]/" (url/resolve nil "http://[0:0::1]/")))
  (is (nil? (url/resolve nil "http://exa mple.org/")))
  (is (= "file:///C:/demo" (url/resolve nil "file:///C|/demo"))))

(deftest one-base-for-many-urls
  (let [resolve (url/resolver "https://example.com/blog/post")]
    (is (= ["https://example.com/blog/a" "https://example.com/b" "https://x.example/"]
           (map resolve ["a" "/b" "https://x.example/"])))))

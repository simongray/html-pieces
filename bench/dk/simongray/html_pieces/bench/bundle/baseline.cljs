(ns dk.simongray.html-pieces.bench.bundle.baseline
  "An app that uses the collections of cljs.core and prints them, as most
  ClojureScript apps do, which the size of every other ClojureScript
  bundle is measured against.")

(defn init
  "Log the HTML of the page in a few collections, printed."
  []
  (let [s (.. js/document -body -innerHTML)]
    (js/console.log (pr-str {:html  s
                             :tags  #{:p :br}
                             :nodes [(list s)]}))))

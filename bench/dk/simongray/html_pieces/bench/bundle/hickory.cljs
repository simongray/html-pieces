(ns dk.simongray.html-pieces.bench.bundle.hickory
  "An app that parses HTML into Hiccup with hickory."
  (:require [hickory.core :as hickory]))

(defn init
  "Log the HTML of the page, parsed into Hiccup."
  []
  (let [s (.. js/document -body -innerHTML)]
    (js/console.log (mapv hickory/as-hiccup (hickory/parse-fragment s)))))

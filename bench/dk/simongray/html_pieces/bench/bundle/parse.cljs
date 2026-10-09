(ns dk.simongray.html-pieces.bench.bundle.parse
  "An app that parses HTML with html-pieces."
  (:require [dk.simongray.html-pieces :as html]))

(defn init
  "Log the HTML of the page, parsed."
  []
  (js/console.log (html/parse (.. js/document -body -innerHTML))))

(ns dk.simongray.html-pieces.bench.bundle.hiccup
  "An app that parses and sanitizes HTML with html-pieces."
  (:require [dk.simongray.html-pieces :as html]))

(defn init
  "Log the HTML of the page as sanitized Hiccup."
  []
  (js/console.log (html/hiccup (.. js/document -body -innerHTML))))

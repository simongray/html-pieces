(ns dk.simongray.html-pieces.bench.bundle.markdown
  "An app that renders HTML as Markdown with html-pieces."
  (:require [dk.simongray.html-pieces :as html]))

(defn init
  "Log the HTML of the page as Markdown."
  []
  (js/console.log (html/markdown (.. js/document -body -innerHTML))))

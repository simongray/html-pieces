(ns dk.simongray.html-pieces.bench.bundle.all
  "An app that uses every function of html-pieces."
  (:require [dk.simongray.html-pieces :as html]))

(defn init
  "Log the HTML of the page parsed, as Hiccup, as text, as Markdown and as
  sanitized HTML."
  []
  (let [s (.. js/document -body -innerHTML)]
    (js/console.log (html/parse s)
                    (html/hiccup s)
                    (html/text s)
                    (html/markdown s)
                    (html/sanitize s))))

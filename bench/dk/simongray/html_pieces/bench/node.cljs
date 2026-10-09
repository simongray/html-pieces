(ns dk.simongray.html-pieces.bench.node
  "The benchmarks in Node: the time per call of html-pieces and comparable
  libraries, for each area and fixture.

  The script takes the options as EDN in its first argument, prints each
  result to stderr as it comes, and prints all of them as EDN to stdout."
  (:require ["html-to-text" :as html-to-text]
            ["htmlparser2" :as htmlparser2]
            ["parse5" :as parse5]
            ["sanitize-html" :as sanitize-html]
            ["turndown" :as Turndown]
            [cljs.reader :as reader]
            [dk.simongray.html-pieces :as html]
            [dk.simongray.html-pieces.bench.areas :as areas]
            [dk.simongray.html-pieces.bench.harness :as harness]
            [replicant.string :as replicant]))

;; Each library is set up once, outside the calls that are timed, as an
;; app would set it up.

(def turndown
  "The HTML to Markdown converter of turndown, with its defaults."
  (Turndown.))

(def benchmarks
  "The libraries that the Node benchmarks compare, by area, each with the
  function that's timed. It takes the input that the area makes of a
  fixture."
  [{:area :parse     :lib "html-pieces"   :f html/parse}
   {:area :parse     :lib "parse5"        :f #(parse5/parseFragment %)}
   {:area :parse     :lib "htmlparser2"   :f #(htmlparser2/parseDocument %)}
   {:area :sanitize  :lib "html-pieces"   :f html/sanitize}
   {:area :sanitize  :lib "sanitize-html" :f #(sanitize-html %)}
   {:area :text      :lib "html-pieces"   :f html/text}
   {:area :text      :lib "html-to-text"  :f #(html-to-text/convert %)}
   {:area :markdown  :lib "html-pieces"   :f html/markdown}
   {:area :markdown  :lib "turndown"      :f #(.turndown ^js turndown %)}
   {:area :serialize :lib "html-pieces"   :f html/sanitize}
   {:area :serialize :lib "replicant"     :f replicant/render}])

(defn eprintln
  "Print `xs` to stderr, so that stdout holds only the results."
  [& xs]
  (.write js/process.stderr (str (apply str xs) "\n")))

(defn measure
  "The time per call of the function of `benchmark` with its input, timed
  by `timing` in `round`, and printed."
  [{:keys [area lib fixture f input] :as benchmark} timing round]
  (let [{:keys [median] :as time} (harness/time-per-call f input timing)]
    (eprintln "Node  " round "  " (.padEnd (name area) 10) (.padEnd lib 15)
              (.padEnd fixture 11) (.padStart (.toFixed median 0) 12) " ns")
    {:platform :node
     :area     area
     :lib      lib
     :fixture  fixture
     :time     time}))

(defn run
  "The results of the Node benchmarks for the :areas and :fixtures of
  `opts`, timed by its :timing."
  [{:keys [timing] :as opts}]
  (harness/fastest-rounds #(measure %1 timing %2)
                          (areas/cases benchmarks opts)
                          timing))

(defn main
  "Run the benchmarks by the options in the EDN `opts`, and print the
  results to stdout."
  [opts]
  (prn (vec (run (reader/read-string opts)))))

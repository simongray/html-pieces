(ns dk.simongray.html-pieces.bench.jvm
  "The benchmarks on the JVM: the time and the memory allocated per call of
  html-pieces and comparable libraries, for each area and fixture."
  (:require [dk.simongray.html-pieces :as html]
            [dk.simongray.html-pieces.bench.areas :as areas]
            [dk.simongray.html-pieces.bench.harness :as harness]
            [hiccup2.core :as hiccup2]
            [hickory.core :as hickory]
            [huff2.core :as huff]
            [pl.danieljanus.tagsoup :as tagsoup]
            [replicant.string :as replicant])
  (:import [com.sun.management ThreadMXBean]
           [com.vladsch.flexmark.html2md.converter FlexmarkHtmlConverter]
           [io.github.furstenheim CopyDown]
           [java.lang.management ManagementFactory]
           [org.jsoup Jsoup]
           [org.jsoup.safety Safelist]
           [org.owasp.html PolicyFactory Sanitizers]))

;; Each library is set up once, outside the calls that are timed, as an
;; app would set it up.

(def ^Safelist jsoup-safelist
  "The safelist of jsoup that's closest to what html-pieces keeps."
  (Safelist/relaxed))

(def ^PolicyFactory owasp-policy
  "The policy of the OWASP sanitizer that's closest to what html-pieces
  keeps."
  (-> Sanitizers/FORMATTING
      (.and Sanitizers/BLOCKS)
      (.and Sanitizers/LINKS)
      (.and Sanitizers/IMAGES)
      (.and Sanitizers/TABLES)))

(def ^FlexmarkHtmlConverter flexmark
  "The HTML to Markdown converter of flexmark, with its defaults."
  (.build (FlexmarkHtmlConverter/builder)))

(def ^CopyDown copy-down
  "The HTML to Markdown converter of copy_down, with its defaults."
  (CopyDown.))

(def benchmarks
  "The libraries that the JVM benchmarks compare, by area, each with the
  function that's timed. It takes the input that the area makes of a
  fixture."
  [{:area :parse     :lib "html-pieces" :f html/parse}
   {:area :parse     :lib "hickory"     :f #(mapv hickory/as-hiccup
                                                  (hickory/parse-fragment %))}
   {:area :parse     :lib "clj-tagsoup" :f tagsoup/parse-string}
   {:area :sanitize  :lib "html-pieces" :f html/sanitize}
   {:area :sanitize  :lib "jsoup"       :f #(Jsoup/clean ^String %
                                                         jsoup-safelist)}
   {:area :sanitize  :lib "OWASP"       :f #(.sanitize owasp-policy %)}
   {:area :text      :lib "html-pieces" :f html/text}
   {:area :text      :lib "jsoup"       :f #(.text
                                              (Jsoup/parseBodyFragment %))}
   {:area :markdown  :lib "html-pieces" :f html/markdown}
   {:area :markdown  :lib "flexmark"    :f #(.convert flexmark ^String %)}
   {:area :markdown  :lib "copy_down"   :f #(.convert copy-down ^String %)}
   {:area :serialize :lib "html-pieces" :f html/sanitize}
   {:area :serialize :lib "hiccup"      :f #(str (hiccup2/html %))}
   {:area :serialize :lib "huff"        :f #(str (huff/html %))}
   {:area :serialize :lib "replicant"   :f replicant/render}])

(defn allocated-per-call
  "The bytes that the current thread allocates per call of `f` with `x`, on
  average over `n` calls."
  [f x n]
  (let [^ThreadMXBean mx (ManagementFactory/getThreadMXBean)
        before           (.getCurrentThreadAllocatedBytes mx)]
    (dotimes [_ n]
      (vreset! harness/sink (f x)))
    (double (/ (- (.getCurrentThreadAllocatedBytes mx) before) n))))

(defn measure
  "The time and the memory per call of the function of `benchmark` with its
  input, timed by `timing` in `round`, and printed."
  [{:keys [area lib fixture f input] :as benchmark} timing round]
  (System/gc)
  (let [{:keys [calls median] :as time} (harness/time-per-call f input timing)]
    (println (format "JVM   %d  %-9s %-14s %-11s %12.0f ns"
                     round (name area) lib fixture median))
    {:platform :jvm
     :area     area
     :lib      lib
     :fixture  fixture
     :time     time
     :bytes    (allocated-per-call f input calls)}))

(defn run
  "The results of the JVM benchmarks for the :areas and :fixtures of
  `opts`, timed by its :timing."
  [{:keys [timing] :as opts}]
  (harness/fastest-rounds #(measure %1 timing %2)
                          (areas/cases benchmarks opts)
                          timing))

(comment
  (run {:areas    #{:parse}
        :fixtures [{:id "comment"}]
        :timing   harness/quick-opts})
  #_.)

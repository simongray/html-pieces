(ns dk.simongray.html-pieces.bench.bundle
  "The builds of the benchmarks: the Node script, and a minified browser
  bundle of each app, whose size is measured against a baseline app.

  The ClojureScript apps are this namespace's children, built by
  shadow-cljs, and the JavaScript apps are in bench/bundle, built by
  esbuild."
  (:require [clojure.java.io :as io]
            [shadow.cljs.devtools.api :as shadow])
  (:import [java.lang ProcessBuilder$Redirect]
           [java.nio.file Files]
           [java.util.zip Deflater]))

(def node-build
  "The shadow-cljs build of the script that runs the Node benchmarks."
  {:build-id  :bench-node
   :target    :node-script
   :main      'dk.simongray.html-pieces.bench.node/main
   :output-to "target/bench/node.js"})

(def bundles
  "The apps whose bundles are measured, by the tool that builds them and
  the name of their namespace or file. The baseline of each tool comes
  first."
  [{:tool :shadow  :id "baseline"      :label "ClojureScript app"}
   {:tool :shadow  :id "parse"         :label "html-pieces, parse"}
   {:tool :shadow  :id "hiccup"        :label "html-pieces, hiccup"}
   {:tool :shadow  :id "markdown"      :label "html-pieces, markdown"}
   {:tool :shadow  :id "all"           :label "html-pieces, every function"}
   {:tool :shadow  :id "hickory"       :label "hickory"}
   {:tool :esbuild :id "baseline"      :label "JavaScript app"}
   {:tool :esbuild :id "parse5"        :label "parse5"}
   {:tool :esbuild :id "dompurify"     :label "DOMPurify"}
   {:tool :esbuild :id "sanitize-html" :label "sanitize-html"}
   {:tool :esbuild :id "turndown"      :label "turndown"}])

(defn output-file
  "The file of the built `bundle`."
  [{:keys [tool id] :as bundle}]
  (io/file "target/bench/bundle" (name tool) id "main.js"))

(defn shadow-build
  "The shadow-cljs build of the ClojureScript app of `bundle`."
  [{:keys [id] :as bundle}]
  {:build-id   (keyword (str "bench-bundle-" id))
   :target     :browser
   :output-dir (str (.getParentFile (output-file bundle)))
   :modules    {:main {:init-fn (symbol (str "dk.simongray.html-pieces"
                                             ".bench.bundle." id)
                                        "init")}}})

(defn shadow-release!
  "Make a release build of each of the shadow-cljs `builds`."
  [builds]
  (shadow/with-runtime
    (doseq [build builds]
      (shadow/release* build {}))))

;; esbuild is what a JavaScript app would bundle with, and shadow-cljs
;; can't bundle parse5: Closure doesn't read its export * as
(defn esbuild!
  "Bundle the JavaScript app of `bundle` with esbuild, minified."
  [{:keys [id] :as bundle}]
  (let [process (-> (ProcessBuilder.
                     ["node_modules/.bin/esbuild" (str "bench/bundle/" id ".js")
                      "--bundle" "--minify" "--platform=browser"
                      "--format=iife" "--log-level=warning"
                      (str "--outfile=" (output-file bundle))])
                    (.redirectOutput ProcessBuilder$Redirect/INHERIT)
                    (.redirectError ProcessBuilder$Redirect/INHERIT)
                    (.start))]
    (when-not (zero? (.waitFor process))
      (throw (ex-info (str "esbuild couldn't bundle " id) bundle)))))

(defn build!
  "Build the Node script when `node?`, and every bundle when `bundles?`."
  [node? bundles?]
  (shadow-release! (cond-> []
                     node?    (conj node-build)
                     bundles? (into (comp (filter (comp #{:shadow} :tool))
                                          (map shadow-build))
                                    bundles)))
  (when bundles?
    (run! esbuild! (filter (comp #{:esbuild} :tool) bundles))))

;; gzip -9, as a server would send it: the deflated bytes, and the 18
;; bytes of the gzip header and trailer
(defn gzipped-size
  "The size of the bytes `bs` when gzipped."
  [^bytes bs]
  (let [deflater (doto (Deflater. 9 true)
                   (.setInput bs)
                   (.finish))
        buffer   (byte-array 65536)]
    (loop [n 0]
      (if (.finished deflater)
        (do (.end deflater)
            (+ n 18))
        (recur (+ n (.deflate deflater buffer)))))))

(defn sizes
  "The bundles, each with its size minified and gzipped."
  []
  (for [bundle bundles
        :let [bs (Files/readAllBytes (.toPath (output-file bundle)))]]
    (assoc bundle
           :bytes (alength bs)
           :gzip  (gzipped-size bs))))

(comment
  (build! false true)
  (sizes)
  #_.)

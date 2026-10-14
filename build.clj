(ns build
  "Build and release the library.

      clojure -T:build jar       ; target/html-pieces-<version>.jar with
                                 ; its pom
      clojure -T:build install   ; into the local Maven repository
      clojure -T:build deploy    ; to Clojars, with CLOJARS_USERNAME and
                                 ; CLOJARS_PASSWORD (a deploy token) set

  The version is that of the Git tag on the commit. To release, set the
  version in the install snippets of the README and doc, and commit. Tag
  the commit, e.g. as v0.1.0, and push both:

      git tag -a v0.1.0 -m 0.1.0
      git push origin master v0.1.0

  Then deploy with the deploy token kept in the macOS Keychain:

      token=$(security find-generic-password -s clojars-deploy -w)
      CLOJARS_USERNAME=simongray CLOJARS_PASSWORD=$token clojure -T:build deploy

  To replace that token with a new one from https://clojars.org/tokens, run
  this and paste the new token at both prompts:

      security add-generic-password -U -a simongray -s clojars-deploy -w"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.build.api :as b]))

(def lib
  "The Maven coordinate of the library."
  'dk.simongray/html-pieces)

(defn- git
  "The output of git with the `args`, trimmed, or an empty string."
  [args]
  (str (b/git-process {:git-args args})))

(def version
  "The version to build: the one that a Git tag on the commit names, e.g.
  0.1.0 for v0.1.0, or else a snapshot of the commit, which deploy
  refuses."
  (if-let [tag (some-> (git "tag --points-at HEAD --list v* --sort=-v:refname")
                       str/split-lines
                       first
                       not-empty)]
    (subs tag 1)
    (str (str/replace (git "describe --tags --match v* --always") #"^v" "")
         "-SNAPSHOT")))

(def class-dir
  "Where the jar's contents are staged."
  "target/classes")

(def jar-file
  "The jar to build."
  (format "target/%s-%s.jar" (name lib) version))

(def basis
  "The project basis, computed on first use."
  (delay (b/create-basis {:project "deps.edn"})))

(def scm-url
  "The repository, for the pom."
  "https://github.com/simongray/html-pieces")

(defn- snippet-versions
  "The versions that the install snippets of the README and the pages in
  doc name, as pairs of a file and a version."
  []
  (let [snippet (re-pattern (str (java.util.regex.Pattern/quote (str lib))
                                 "\\s+\\{:mvn/version \"([^\"]+)\""))
        pages   (->> (.listFiles (io/file "doc"))
                     (filter #(str/ends-with? (.getName ^java.io.File %) ".md"))
                     sort)]
    (for [f     (cons (io/file "README.md") pages)
          :when (.exists ^java.io.File f)
          [_ v] (re-seq snippet (slurp f))]
      [(str f) v])))

(defn- check-release!
  "Throw unless the commit is a release: tagged with a version, with no
  changes beside it, and with that version in every install snippet."
  []
  (when (str/ends-with? version "-SNAPSHOT")
    (throw (ex-info "Tag the commit with its version, e.g. v0.1.0, to deploy it"
                    {:version version})))
  (when (seq (git "status --porcelain --untracked-files=no"))
    (throw (ex-info "Commit the changes before deploying" {})))
  (when-let [stale (seq (remove #(= version (second %)) (snippet-versions)))]
    (throw (ex-info "An install snippet names another version"
                    {:version version :snippets (vec stale)}))))

(defn clean
  "Remove everything built."
  [_]
  (b/delete {:path "target"}))

(defn jar
  "Write the pom and build the jar from the sources in src and the table of
  character references in resources."
  [_]
  (b/delete {:path class-dir})
  (b/write-pom {:class-dir     class-dir
                :lib           lib
                :version       version
                :basis         @basis
                :src-dirs      ["src"]
                :resource-dirs ["resources"]
                :scm           {:url                 scm-url
                                :connection          "scm:git:git://github.com/simongray/html-pieces.git"
                                :developerConnection "scm:git:ssh://git@github.com/simongray/html-pieces.git"
                                :tag                 (str "v" version)}
                :pom-data      [[:description "A Clojure(Script) library for working with embedded pieces of HTML."]
                                [:url scm-url]
                                [:licenses
                                 [:license
                                  [:name "MIT License"]
                                  [:url "https://opensource.org/license/mit"]]]]})
  (b/copy-dir {:src-dirs   ["src" "resources"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file  jar-file}))

(defn install
  "Build the jar and install it in the local Maven repository."
  [_]
  (jar nil)
  (b/install {:basis     @basis
              :lib       lib
              :version   version
              :jar-file  jar-file
              :class-dir class-dir}))

(defn deploy
  "Build the jar and deploy it to Clojars, from a commit that's a release."
  [_]
  (check-release!)
  (jar nil)
  ((requiring-resolve 'deps-deploy.deps-deploy/deploy)
   {:installer :remote
    :artifact  (b/resolve-path jar-file)
    :pom-file  (b/pom-path {:lib lib :class-dir class-dir})}))

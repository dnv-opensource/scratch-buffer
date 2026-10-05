(require '[babashka.fs :as fs]
         '[cheshire.core :as json]
         '[clojure.string :as str]
         '[scratch.content :as content]
         '[scratch.calendar :as calendar]
         '[scratch.github :as github]
         '[scratch.html :as html]
         '[scratch.source-maps :as source-maps]
         '[scratch.router :as router]
         '[scratch.state :as state]
         '[scratch.view :as view])
(import '[java.security MessageDigest]
        '[java.util Base64])

(defn sha [bytes]
  (format "%064x" (java.math.BigInteger. 1 (.digest (MessageDigest/getInstance "SHA-256") bytes))))

(defn csp-hash [text]
  (str "'sha256-" (.encodeToString (Base64/getEncoder)
                                  (.digest (MessageDigest/getInstance "SHA-256") (.getBytes text "UTF-8"))) "'"))

(defn write! [path data]
  (fs/create-dirs (fs/parent path))
  (spit path data))

(let [base (router/normalize-base (System/getenv "BASE_PATH"))
      _ (content/ensure! (or (= base "/") (re-matches #"/[A-Za-z0-9._/-]+/" base)) "Invalid BASE_PATH" {:base base})
      data (content/load-data ".")
      discussion-snapshot (->> (json/parse-string (slurp ".cache/discussions/snapshot.json") true)
                               (github/validate-snapshot! (get-in data [:site :repository])))
      routes (router/routes data)
      template (slurp "index.html")
      styles (slurp "assets/css/site.css")
      theme-script (slurp "assets/theme.js")
      out "_site"
      sources ["src/scratch/router.cljc" "src/scratch/theme.cljc" "src/scratch/meetings.cljc"
               "src/scratch/reading.cljc" "src/scratch/commands.cljc" "src/scratch/github.cljc"
               "src/scratch/constellation.cljc" "src/scratch/parentheses.cljc" "src/scratch/state.cljc" "src/scratch/view.cljc"
               "src/scratch/motion.cljs" "src/scratch/nav.cljs" "src/scratch/effects.cljs"
               "src/scratch/actions.cljs" "src/scratch/keyboard.cljs" "src/scratch/pwa.cljs" "src/scratch/app.cljs"]
      runtime (str (slurp ".cache/vendor/nexus/core.cljc")
                   "\n(ns scratch.generated)\n(def data " (pr-str data) ")\n"
                   (str/join "\n" (map slurp sources)))
      document (fn [route]
                 (-> template
                     (str/replace "{{base}}" base)
                     (str/replace "{{title}}" (html/escape-text (:title route)))
                     (str/replace "{{theme-csp}}" (csp-hash theme-script))
                     (str/replace "{{style-csp}}" (csp-hash styles))
                     (str/replace "{{theme-script}}" theme-script)
                     (str/replace "{{styles}}" styles)
                     (str/replace "{{content}}" (html/render (view/view (state/initial data base (:path route)))))))]
  ;; Only this dedicated, generated output directory is replaced.
  (when (fs/exists? out) (fs/delete-tree out))
  (fs/create-dirs out)
  (fs/copy-tree "assets" (str out "/assets"))
  (fs/copy-tree ".cache/vendor" (str out "/vendor"))
  (doseq [file ["scittle.js.map" "scittle.replicant.js.map"]]
    (write! (str out "/vendor/" file)
            (-> (slurp (str ".cache/vendor/" file))
                (json/parse-string true)
                source-maps/flatten-indexed
                json/generate-string)))
  (doseq [icon (fs/glob ".cache/icons" "*.png")]
    (fs/copy icon (fs/path out "assets/icons" (fs/file-name icon))))
  (doseq [member (:members data) :when (not (:example? member))]
    (let [path (str out (github/avatar-path member))]
      (fs/create-dirs (fs/parent path))
      (fs/copy (fs/path ".cache/avatars" (str (:github member) ".png")) path)))
  (doseq [route routes]
    (write! (str out (:path route) "index.html") (document route)))
  (doseq [[path target] router/redirects]
    (content/ensure! (not= :not-found (:id (router/resolve-route data target)))
                     "Redirect target is not a published buffer" {:path path :target target})
    (write! (str out path "index.html")
            (str/replace (document (router/resolve-route data target))
                         "</head>"
                         (str "<link rel=\"canonical\" href=\"" (html/escape-text (router/url base target)) "\">"
                              "<meta http-equiv=\"refresh\" content=\"0;url="
                              (html/escape-text (router/url base target)) "\"></head>"))))
  (calendar/publish! out data)
  (write! (str out "/404.html") (document (router/resolve-route data "/not-found/")))
  (write! (str out "/app.cljs") runtime)
  (write! "data/generated/site.edn" (pr-str data))
  (write! (str out "/data/generated/site.edn") (pr-str data))
  (write! (str out github/snapshot-path) (json/generate-string discussion-snapshot))
  (let [manifest (-> (json/parse-string (slurp "manifest.webmanifest") true)
                     (assoc :id base :scope base :start_url base)
                     (update :icons #(mapv (fn [icon] (update icon :src (fn [path] (router/url base (str "/" path))))) %)))]
    (write! (str out "/manifest.webmanifest") (json/generate-string manifest {:pretty true})))
  (let [files (sort (filter fs/regular-file? (fs/glob out "**")))
        paths (mapv #(str/replace (str (fs/relativize out %)) #"/index\.html$|^index\.html$" "/")
                    (remove #(str/ends-with? (str %) ".map") files))
        paths (mapv #(str/replace % #"^/" "") paths)
        fingerprint (sha (.getBytes (str/join "" (map #(sha (fs/read-all-bytes %)) files)) "UTF-8"))]
    (write! (str out "/sw.js")
            (-> (slurp "sw.js")
                (str/replace "{{revision}}" (subs fingerprint 0 16))
                (str/replace "{{precache}}" (json/generate-string paths)))))
  (println "Built" (count routes) "canonical buffers at" base "into" out))

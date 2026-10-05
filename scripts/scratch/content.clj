(ns scratch.content
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [scratch.calendar :as calendar]
            [scratch.constellation :as constellation]
            [scratch.router :as router]
            [scratch.state :as state]
            [scratch.view :as view])
  (:import [java.io PushbackReader StringReader]
           [java.net URI]
           [java.time LocalDate Year YearMonth ZoneId]))

(defn ensure! [condition message data]
  (when-not condition (throw (ex-info message data))))

(defn read-record [path]
  (try
    (with-open [reader (PushbackReader. (StringReader. (slurp (str path))))]
      (let [options {:eof ::eof :readers {} :default (fn [tag _] (throw (ex-info "EDN tags are not supported" {:tag tag})))}
            record (edn/read options reader)]
        (ensure! (map? record) "Record must be an EDN map" {:file (str path)})
        (ensure! (= ::eof (edn/read options reader)) "File must contain exactly one record" {:file (str path)})
        record))
    (catch Exception e
      (throw (ex-info (str path ": " (ex-message e)) {:file (str path)} e)))))

(defn text? [x]
  (and (string? x) (not (str/blank? x))))

(defn github-name? [s]
  (and (string? s) (<= (count s) 39)
       (re-matches #"[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*" s)))

(defn valid-date? [value parser]
  (and (string? value)
       (try (parser value) true (catch Exception _ false))))

(defn https-url? [value]
  (and (string? value)
       (try
         (let [uri (URI. value)]
           (and (= "https" (.getScheme uri)) (text? (.getHost uri)) (nil? (.getUserInfo uri))))
         (catch Exception _ false))))

(defn keywords? [x]
  (and (set? x) (every? #(and (keyword? %) (nil? (namespace %))
                              (re-matches #"[a-z0-9][a-z0-9-]*" (name %))) x)))

(def member-keys #{:github :full-name :joined :emacs-since :favorite-command :interests :bio :example?})
(def common-keys #{:id :title :description :body :tags :url :discussion-number :example? :author})
(def kind-keys
  {:pages common-keys
   :meetings (into common-keys #{:date :time :timezone :duration-minutes :status :speaker :join-url :location :notes-url :recording-url})
   :talks (into common-keys #{:speaker :meeting-id})
   :snippets (into common-keys #{:code :language :command :tip?})})

(defn validate-member! [member filename]
  (ensure! (every? member-keys (keys member)) "Unsupported member fields; profile information must be opt-in and allowed" {:file filename})
  (ensure! (github-name? (:github member)) "Invalid GitHub handle" {:file filename})
  (ensure! (= filename (str (:github member) ".edn")) "Member filename must match GitHub handle" {:file filename})
  (ensure! (and (re-matches #"\d{4}-\d{2}" (str (:joined member)))
                (valid-date? (:joined member) #(YearMonth/parse %))) "Invalid joined month (YYYY-MM)" {:file filename})
  (when (contains? member :emacs-since)
    (ensure! (and (string? (:emacs-since member))
                  (re-matches #"\d{4}" (:emacs-since member))
                  (valid-date? (:emacs-since member) #(Year/parse %))
                  (<= 1976 (parse-long (:emacs-since member)) (.getValue (Year/now))))
             "Emacs-started year must be YYYY, between 1976 and the current year" {:file filename :field :emacs-since}))
  (doseq [k [:full-name :favorite-command :bio] :when (contains? member k)]
    (ensure! (text? (get member k)) "Optional member text must not be empty" {:file filename :field k}))
  (when (contains? member :interests)
    (ensure! (keywords? (:interests member)) "Interests must be a set of keywords" {:file filename}))
  (when (contains? member :example?)
    (ensure! (boolean? (:example? member)) "example? must be boolean" {:file filename}))
  member)

(defn validate-content! [kind record filename]
  (ensure! (every? (get kind-keys kind) (keys record)) "Unsupported content field" {:file filename :kind kind})
  (ensure! (and (string? (:id record)) (re-matches #"[a-z0-9]+(?:-[a-z0-9]+)*" (:id record))) "Invalid content ID" {:file filename})
  (ensure! (= filename (str (:id record) ".edn")) "Content filename must match its ID" {:file filename})
  (doseq [k [:title :description]]
    (ensure! (text? (get record k)) "Missing required text" {:file filename :field k}))
  (ensure! (and (vector? (:body record)) (seq (:body record)) (every? text? (:body record)))
           "Body must be a nonempty vector of plain-text paragraphs" {:file filename})
  (doseq [k [:command :code :language :location] :when (contains? record k)]
    (ensure! (text? (get record k)) "Optional content text must not be empty" {:file filename :field k}))
  (doseq [k [:example? :tip?] :when (contains? record k)]
    (ensure! (boolean? (get record k)) "Flag must be boolean" {:file filename :field k}))
  (when (contains? record :tags)
    (ensure! (keywords? (:tags record)) "Tags must be a set of keywords" {:file filename}))
  (doseq [k [:url :join-url :notes-url :recording-url] :when (contains? record k)]
    (ensure! (https-url? (get record k)) "URL must be absolute HTTPS, without credentials" {:file filename :field k}))
  (when (contains? record :discussion-number)
    (ensure! (pos-int? (:discussion-number record)) "Discussion number must be a positive integer" {:file filename}))
  (when (:tip? record)
    (ensure! (text? (:command record)) "A tip needs a command" {:file filename}))
  (when (= kind :meetings)
    (ensure! (and (re-matches #"\d{4}-\d{2}-\d{2}" (str (:date record)))
                  (valid-date? (:date record) #(LocalDate/parse %))) "Invalid meeting date" {:file filename})
    (ensure! (and (string? (:time record)) (re-matches #"(?:[01]\d|2[0-3]):[0-5]\d" (:time record))) "Invalid meeting time" {:file filename})
    (ensure! (valid-date? (:timezone record) #(ZoneId/of %)) "Invalid IANA time zone" {:file filename})
    (ensure! (pos-int? (:duration-minutes record)) "Meeting duration must be a positive integer" {:file filename})
    (ensure! (#{:scheduled :past :example} (:status record)) "Invalid meeting status" {:file filename})
    (ensure! (= (= :example (:status record)) (true? (:example? record))) "Example meetings must be explicitly labeled" {:file filename})
    (when (= :scheduled (:status record))
      (ensure! (or (:join-url record) (text? (:location record))) "Scheduled meetings need joining details" {:file filename}))
    (calendar/start record))
  record)

(defn unique! [kind records key-fn]
  (let [ids (map key-fn records)]
    (ensure! (= (count ids) (count (set ids))) "Duplicate IDs" {:collection kind :ids ids})))

(defn validate-constellation! [record]
  (ensure! (= #{:names} (set (keys record))) "Constellation data must contain only :names" {})
  (ensure! (and (vector? (:names record))
                (>= (count (:names record)) (* 2 constellation/slot-count))
                (every? #(and (string? %) (re-matches #"[a-z][a-z0-9-]{0,23}" %)) (:names record)))
           "Constellation needs at least 36 valid Emacs names, each at most 24 characters" {})
  (unique! :constellation (:names record) identity)
  record)

(defn validate-references! [data]
  (let [members (set (map :github (:members data)))
        meetings (set (map :id (:meetings data)))]
    (doseq [kind (keys kind-keys) record (get data kind)]
      (doseq [k [:speaker :author] :when (contains? record k)]
        (ensure! (members (get record k)) "Unknown member reference" {:id (:id record) :field k :value (get record k)}))
      (when (:meeting-id record)
        (ensure! (meetings (:meeting-id record)) "Unknown meeting reference" {:id (:id record)})))))

(defn visible-text [node]
  (cond
    (and (vector? node) (keyword? (first node)))
    (let [children (next node)
          attrs (when (map? (first children)) (first children))]
      (if (or (#{"true" true} (:aria-hidden attrs))
              (#{"true" true} (:data-search-exclude attrs)))
        ""
        (visible-text (if attrs (next children) children))))
    (sequential? node) (str/join " " (map visible-text node))
    (nil? node) ""
    :else (str node)))

(defn search-index [data]
  (mapv
   (fn [route]
     (let [record (if (:kind route)
                    (router/record data route)
                    (some #(when (= (:id %) (name (:id route))) %) (:pages data)))
           description (or (:description record) (:bio record) (:title route))
           rendered-text (visible-text (view/buffer-view (state/initial data "/" (:path route))))
           text (str/join " " (concat [(:title route) description rendered-text (:github record) (:author record) (:speaker record) (:favorite-command record) (:command record) (:code record)]
                                     (:body record)
                                     (map name (:tags record))
                                     (map name (:interests record))))]
       {:path (:path route) :title (:title route) :description description
        :type (name (or (:kind route) :page)) :text (str/lower-case text)}))
   (remove #(= :messages (:id %)) (router/routes data))))

(defn load-data [root]
  (let [site (read-record (fs/path root "site.edn"))
        _ (ensure! (and (https-url? (:repository site))
                        (re-matches #"https://github\.com/[A-Za-z0-9-]+/[A-Za-z0-9._-]+" (:repository site)))
                   "Repository must be a public GitHub HTTPS URL" {})
        _ (doseq [k [:name :description :status]] (ensure! (text? (get site k)) "Missing site text" {:field k}))
        _ (ensure! (and (https-url? (:teams-url site))
                        (= "teams.microsoft.com" (.getHost (URI. (:teams-url site)))))
                   "Teams chat must use a Microsoft Teams HTTPS link" {})
        members (mapv #(validate-member! (read-record %) (str (fs/file-name %)))
                      (sort (fs/glob (fs/path root "members") "*.edn")))
        content (into {}
                      (for [kind (keys kind-keys)]
                        [kind (mapv #(validate-content! kind (read-record %) (str (fs/file-name %)))
                                    (sort (fs/glob (fs/path root "content" (name kind)) "*.edn")))]))
        illustration (validate-constellation! (read-record (fs/path root "content/constellation.edn")))
        data (-> content
                 (update :meetings #(mapv calendar/enrich %))
                 (assoc :members members :site site :constellation illustration :today (str (LocalDate/now))))]
    (unique! :members members #(str/lower-case (:github %)))
    (doseq [kind (keys kind-keys)] (unique! kind (get data kind) :id))
    (ensure! (= #{"scratch" "about"} (set (map :id (:pages data))))
             "Source pages must include exactly scratch and about; new primary pages need router/view support" {})
    (validate-references! data)
    (assoc data :search (search-index data))))

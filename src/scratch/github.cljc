(ns scratch.github
  (:require [clojure.string :as str]
            [scratch.router :as router]))

(def snapshot-path "/data/generated/discussions.json")

(defn page-record [state]
  (or (router/record (:content state) (:route state))
      (some #(when (= (:id %) (name (get-in state [:route :id]))) %)
            (get-in state [:content :pages]))))

(defn discussion-number [state]
  (:discussion-number (page-record state)))

(defn readable? [state]
  (not (#{:help :search :messages :not-found} (get-in state [:route :id]))))

(defn avatar-path [member]
  (str "/assets/avatars/" (:github member) ".png"))

(defn discussion-url [state kind]
  (let [repository (get-in state [:content :site :repository])
        discussion (discussion-number state)]
    (case kind
      :source repository
      :page (str repository "/discussions" (when discussion (str "/" discussion)))
      (str repository "/discussions/new"))))

(defn ensure! [condition message]
  (when-not condition (throw (ex-info message {}))))

(defn timestamp? [value]
  (and (string? value)
       (re-matches #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{3})?Z" value)
       #?(:clj (try (java.time.Instant/parse value) true (catch Exception _ false))
          :cljs (not (js/isNaN (js/Date.parse value))))))

(defn text? [value] (and (string? value) (not (str/blank? value))))

(defn fields! [value fields]
  (ensure! (and (map? value) (= fields (set (keys value)))) "Invalid discussion snapshot fields."))

(defn validate-author! [author]
  (ensure! (or (nil? author) (map? author)) "Invalid discussion author.")
  (when author
    (fields! author #{:login :url})
    (ensure! (and (text? (:login author))
                  (re-matches #"[A-Za-z0-9][A-Za-z0-9-]*(?:\[bot\])?" (:login author))
                  (string? (:url author))
                  (re-matches #"https://github\.com/(?:apps/)?[A-Za-z0-9][A-Za-z0-9-]*" (:url author)))
             "Invalid discussion author.")))

(defn validate-comment! [thread-url reply? comment]
  (fields! comment (cond-> #{:url :body :created-at :author} (not reply?) (conj :replies)))
  (ensure! (and (string? (:body comment)) (timestamp? (:created-at comment))
                (string? (:url comment)) (str/starts-with? (:url comment) (str thread-url "#discussioncomment-"))
                (re-matches #"\d+" (subs (:url comment) (count (str thread-url "#discussioncomment-")))))
           "Invalid discussion comment.")
  (validate-author! (:author comment))
  (when-not reply?
    (ensure! (vector? (:replies comment)) "Invalid discussion replies.")
    (doseq [reply (:replies comment)] (validate-comment! thread-url true reply))))

(defn validate-snapshot! [repository snapshot]
  (fields! snapshot #{:version :repository :generated-at :status :discussions})
  (ensure! (and (= 1 (:version snapshot)) (= repository (:repository snapshot))
                (#{"ready" "disabled" "unavailable"} (:status snapshot))
                (vector? (:discussions snapshot))
                (if (= "unavailable" (:status snapshot))
                  (nil? (:generated-at snapshot))
                  (timestamp? (:generated-at snapshot)))
                (or (= "ready" (:status snapshot)) (empty? (:discussions snapshot))))
           "Invalid discussion snapshot metadata.")
  (let [numbers (map :number (:discussions snapshot))]
    (ensure! (= (count numbers) (count (set numbers))) "Duplicate discussion numbers."))
  (doseq [thread (:discussions snapshot)]
    (fields! thread #{:number :title :url :body :created-at :updated-at :author :comments})
    (ensure! (and (pos-int? (:number thread)) (text? (:title thread)) (string? (:body thread))
                  (= (:url thread) (str repository "/discussions/" (:number thread)))
                  (timestamp? (:created-at thread)) (timestamp? (:updated-at thread))
                  (vector? (:comments thread)))
             "Invalid discussion thread.")
    (validate-author! (:author thread))
    (doseq [comment (:comments thread)] (validate-comment! (:url thread) false comment))
    (let [urls (map :url (mapcat #(cons % (:replies %)) (:comments thread)))]
      (ensure! (= (count urls) (count (set urls))) "Duplicate discussion comments.")))
  snapshot)

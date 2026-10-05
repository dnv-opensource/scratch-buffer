(ns scratch.router
  (:require [clojure.string :as str]))

(def pages
  [{:id :scratch :path "/" :buffer "*scratch-buffer*" :title "DNV Emacs User Group"}
   {:id :about :path "/about/" :buffer "*about*" :title "About the group"}
   {:id :meetings :path "/meetings/" :buffer "*meetings*" :title "Meetings"}
   {:id :people :path "/people/" :buffer "*people*" :title "People"}
   {:id :snippets :path "/snippets/" :buffer "*snippets*" :title "Snippets & tips"}
   {:id :help :path "/help/" :buffer "*help*" :title "A little help"}
   {:id :search :path "/search/" :buffer "*search*" :title "Search the site"}
   {:id :messages :path "/messages/" :buffer "*Messages*" :title "Messages"}])

(def collections
  [[:meetings :meeting "/meetings/"]
   [:talks :talk "/meetings/talks/"]
   [:snippets :snippet "/snippets/"]])

(def redirects
  {"/join/" "/people/#join"
   "/packages/" "/snippets/"
   "/packages/eglot/" "/snippets/eglot/"
   "/packages/magit/" "/snippets/magit/"
   "/talks/" "/meetings/#talks"})

(defn normalize-base [base]
  (let [s (str/replace (or base "/") #"^/+|/+$" "")]
    (if (str/blank? s) "/" (str "/" s "/"))))

(defn url [base path]
  (str (normalize-base base) (str/replace path #"^/" "")))

(defn normalize-path [path]
  (let [p (-> (or path "/")
              (str/split #"[?#]" 2) first
              (str/replace #"/index\.html$" "/"))]
    (if (= p "/") "/" (str (str/replace p #"/+$" "") "/"))))

(defn local-path [base pathname]
  (let [base (normalize-base base)
        root (str/replace base #"/$" "")]
    (cond
      (= pathname root) "/"
      (str/starts-with? pathname base)
      (normalize-path (str "/" (subs pathname (count base))))
      :else nil)))

(defn destination [path]
  (let [[pathname fragment] (str/split (or path "/") #"#" 2)
        path (normalize-path pathname)]
    (or (get redirects path)
        (str path (when (seq fragment) (str "#" fragment))))))

(defn member-name [member]
  (or (:full-name member) (:github member)))

(defn routes [data]
  (vec
   (concat
    pages
    (for [[collection kind prefix] collections
          record (get data collection)]
      {:id kind :kind kind :record-id (:id record)
       :path (str prefix (:id record) "/")
       :buffer (str "*" (name kind) "/" (:id record) "*")
       :title (:title record)})
    (for [member (:members data)]
      {:id :person :kind :person :record-id (:github member)
       :path (str "/people/" (:github member) "/")
       :buffer (str "*person/" (:github member) "*")
       :title (member-name member)}))))

(defn resolve-route [data path]
  (let [path (normalize-path (destination path))]
    (or (some #(when (= path (:path %)) %) (routes data))
        {:id :not-found :path path :buffer "*Messages*" :title "Buffer not found"})))

(defn record [data route]
  (if (= :person (:kind route))
    (some #(when (= (:github %) (:record-id route)) %) (:members data))
    (some #(when (= (:id %) (:record-id route)) %)
          (get data (keyword (str (name (or (:kind route) :none)) "s"))))))

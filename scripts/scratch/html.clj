(ns scratch.html
  (:require [clojure.string :as str]))

(defn escape-text [value]
  (-> (str value)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'" "&#39;")))

(def void-tags #{:input :meta :link :img :br :hr})
(def boolean-attrs #{:disabled :checked :selected :multiple :required :autofocus :hidden})

(declare render)

(defn render [node]
  (cond
    (nil? node) ""
    (and (vector? node) (keyword? (first node)))
    (let [[tag & rest] node
          [attrs children] (if (map? (first rest)) [(first rest) (next rest)] [{} rest])
          attributes (apply str
                            (for [[k v] (sort-by (comp str key) attrs)
                                  :when (and (not= k :on) (nil? (namespace k)) (some? v)
                                             (not (and (boolean-attrs k) (false? v))))]
                              (str " " (name k) "=\"" (escape-text v) "\"")))]
      (str "<" (name tag) attributes ">"
           (when-not (void-tags tag)
             (str (apply str (map render children)) "</" (name tag) ">"))))
    (sequential? node) (apply str (map render node))
    :else (escape-text node)))

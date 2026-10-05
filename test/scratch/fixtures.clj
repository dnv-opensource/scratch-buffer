(ns scratch.fixtures
  (:require [scratch.calendar :as calendar]
            [scratch.content :as content]))

(def community (content/read-record "test/fixtures/community.edn"))

(defn with-community [data]
  (doseq [member (:members community)]
    (content/validate-member! member (str (:github member) ".edn")))
  (doseq [kind [:meetings :talks] record (get community kind)]
    (content/validate-content! kind record (str (:id record) ".edn")))
  (let [data (reduce (fn [data kind]
                       (update data kind into (get community kind)))
                     data [:members :meetings :talks])
        data (update data :meetings #(mapv calendar/enrich %))]
    (content/validate-references! data)
    (assoc data :search (content/search-index data))))

(require '[scratch.content :as content])
(let [data (content/load-data ".")]
  (println "Valid records:" (count (:members data)) "members,"
           (reduce + (map #(count (get data %)) (keys content/kind-keys))) "content records."))

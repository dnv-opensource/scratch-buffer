(ns scratch.reading
  (:require [clojure.set :as set]
            [scratch.router :as router]))

(defn related [data route record]
  (->> (for [[collection kind prefix] router/collections
             :when (#{:talks :snippets} collection)
             other (get data collection)
             :let [shared (set/intersection (or (:tags record) #{}) (or (:tags other) #{}))]
             :when (and (seq shared)
                        (not (and (= kind (:kind route)) (= (:id other) (:id record))))
                        (not (and (= :meeting (:kind route)) (= kind :talk) (= (:meeting-id other) (:id record))))
                        (or (:example? record) (not (:example? other))))]
         (assoc other :kind kind :path (str prefix (:id other) "/") :shared-tags shared))
       (sort-by (juxt #(-> % :shared-tags count -) :path))
       (take 3)
       vec))

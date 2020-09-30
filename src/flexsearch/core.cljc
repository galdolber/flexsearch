(ns flexsearch.core
  #?(:clj (:gen-class))
  (:require [clojure.string :as string]
            [clojure.set :as sets]
            [me.tonsky.persistent-sorted-set :as pss]))

#?(:clj (set! *warn-on-reflection* true))

#?(:clj
   (defn ^String normalize [^String str]
     (let [^String normalized (java.text.Normalizer/normalize str java.text.Normalizer$Form/NFD)]
       (clojure.string/replace normalized #"\p{InCombiningDiacriticalMarks}+" "")))

   :cljs (defn ^string normalize-cljs [^string s] (.replace (.normalize s "NFD") #"[\u0300-\u036f]" "")))

(defn default-encoder [value]
  (when value
    (normalize (string/lower-case value))))

(defn filter-words [words filterer]
  (vec (remove filterer words)))

(defn default-splitter [^String s]
  (remove string/blank? (string/split s #"[\W+|[^A-Za-z0-9]]")))

(defn init [{:keys [tokenizer filter encoder] :as options}]
  (assoc options
         :data (pss/sorted-set)
         :index ""
         :ids {}
         :encoder (or encoder default-encoder)
         :tokenizer (if (fn? tokenizer) tokenizer default-splitter)
         :filter (set (mapv encoder filter))))

(def join-char \,)

(defn flex-remove [{:keys [index data ids] :as flex} id-list]
  (loop [[[pos :as pair] & ps] (filter identity (map ids id-list))
         data (transient data)
         index index]
    (if pair
      (let [len (:len (meta pair))]
        (recur ps (disj! data pair)
               (str (subs index 0 pos)
                    (apply str (repeat len " "))
                    (subs index (+ pos len)))))
      (assoc flex :data (persistent! data) :index index))))

(defn flex-add [{:keys [ids encoder] :as flex} pairs]
  (let [updated-pairs (filter (comp ids first) pairs)
        {:keys [ids ^String index data] :as flex} (flex-remove flex (mapv first updated-pairs))]
    (loop [[[id w] & ws] pairs
           pos (.length index)
           data (transient data)
           r (transient [])
           ids (transient ids)]
      (if w
        (let [^String w (encoder w)
              len (.length w)
              pair (with-meta [pos id] {:len len})]
          (recur ws (+ pos len 1) (conj! data pair) (conj! r w) (assoc! ids id pair)))
        (assoc flex
               :ids (persistent! ids)
               :index (str index (string/join join-char (persistent! r)) join-char)
               :data (persistent! data))))))

(defn find-positions [text search]
  (let [search-len (count search)]
    (loop [from 0
           r []]
      (if-let [i (string/index-of text search from)]
        (recur (+ (int i) search-len) (conj r i))
        r))))

(defn flex-search [{:keys [index data tokenizer filter encoder]} search]
  (when (and search data)
    (let [search (encoder search)
          words (tokenizer search)
          words (set (if filter (filter-words words filter) words))]
      (apply sets/intersection
             (mapv #(set (mapv (fn [i]
                                 (last (first (pss/rslice data [(inc i) nil] [-1 nil]))))
                               (find-positions index %))) words)))))

#?(:clj
   (defn -main [search & _]
     (let [sample-data (read-string (slurp "data.edn"))
           data (into {} (map vector (range) sample-data))
           flex (time (flex-add (init {}) data))
           ;;flex (time (flex-add flex {0 "aka Dollars hen"}))
           ;;flex (time (flex-remove flex [0]))
           ]
       (println (mapv sample-data (time (flex-search flex search)))))))

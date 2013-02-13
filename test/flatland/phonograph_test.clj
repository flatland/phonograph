(ns flatland.phonograph-test
  (:use clojure.test
        flatland.phonograph)
  (:require [flatland.useful.utils :refer [returning]]))

(defn temp-file
  "Create a temporary file, deleting the file when the JVM exits."
  ([] (temp-file "temp"))
  ([name] (temp-file name ".tmp"))
  ([name suffix]
     (doto (java.io.File/createTempFile name suffix)
       (.deleteOnExit))))

(defmacro with-temp-file
  "Execute body with file bound to a temporary file, deleting the file when done."
  [[file & args] & body]
  `(let [~file (temp-file ~@args)]
     (returning ~@body
       (.delete ~file))))

(deftest create-empty-file
  (with-temp-file [f]
    (let [phono (create f {:overwrite true} {:count 100 :density 10} {:count 100 :density 100})
          r (get-range (assoc phono :now 1000) 800 1000)]
      (is (= 800  (:from r)))
      (is (= 1000 (:until r)))
      (is (= 10   (:density r)))
      (is (= (repeat 20 nil) (:values r)))
      (let [r (get-range (assoc phono :now 10000) 8000 10000)]
        (is (= 8000  (:from r)))
        (is (= 10000 (:until r)))
        (is (= 100   (:density r)))
        (is (= (repeat 20 nil) (:values r)))))))

(deftest create-and-reopen
  (with-temp-file [f]
    (let [phono (create f {:overwrite true} {:count 100 :density 10} {:count 100 :density 100})]
      (close phono)
      (let [phono (open f)]
        (is (= :sum  (:aggregation phono)))
        (is (= 10000 (:max-retention phono)))
        (is (= 0.0   (:propagation-threshold phono)))
        (is (= [100 100] (map :count (:archives phono))))
        (is (= [10 100]  (map :density (:archives phono))))
        (is (= [36 1236] (map :offset (:archives phono))))))))

(deftest append-data
  (with-temp-file [f]
    (let [phono (create f {:overwrite true}
                        {:count 10 :density 1}
                        {:count 10 :density 10}
                        {:count 10 :density 100})]
      (doseq [[from until] (partition 2 1 (range 100 271 10))]
        (let [phono (assoc phono :now until)
              points (map (fn [t] [t (* t 1.0)])
                          (range from until))]
          (apply append! phono points)
          (let [r (get-range phono from until)]
            (is (= from  (:from r)))
            (is (= until (:until r)))
            (is (= 1     (:density r)))
            (is (= (map last points) (:values r))))))
      (letfn [(check-data [phono]
                (let [r (get-range (assoc phono :now 270) 170 270)]
                  (is (= 170 (:from r)))
                  (is (= 270 (:until r)))
                  (is (= 10  (:density r)))
                  (is (= (range 1745.0 2700.0 100)
                         (:values r))))
                (let [r (get-range (assoc phono :now 270) 100 270)]
                  (is (= 100 (:from r)))
                  (is (= 270 (:until r)))
                  (is (= 100 (:density r)))
                  (is (= [(apply + (range 100.0 200.0))]
                         (:values r)))))]
        (check-data phono)
        (check-data (reopen (close phono)))))))

(deftest misaligned-archives
  (with-temp-file [f]
    (let [phono (create f {:overwrite true}
                        {:count 11 :density 1}
                        {:count 11 :density 10}
                        {:count 11 :density 100})]
      (let [[from until] [989 1000]
            phono (assoc phono :now until)
            points (map (fn [t] [t (* t 1.0)])
                        (range from until))]
        (is (thrown-with-msg? IllegalArgumentException #"does not fit into archive"
              (apply append! phono points)))
        (let [r (get-range phono from until)]
          (is (= from  (:from r)))
          (is (= until (:until r)))
          (is (= 1     (:density r)))
          (is (= (repeat 11 nil) (:values r))))))))
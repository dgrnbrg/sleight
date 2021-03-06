;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns sleight.reader
  (:require
    [clojure.java.io :as io])
  (:import
    [java.io
     Writer
     Reader
     StringReader
     PushbackReader]
    [clojure.lang
     LispReader
     LineNumberingPushbackReader]))

;;;

(def ^:dynamic *newlines* nil)

(def switched-printer
  (delay
    (alter-var-root #'clojure.core/pr-on
      (fn [pr-on]
        (fn [x ^Writer w]
          
          ;; special print instructions
          (when *newlines*
            (.write w (*newlines* (-> x meta :line)))
            (when-let [m (meta x)]
              (.write w (str "^" (pr-str m) " "))))
          
          (pr-on x w))))))

;;;

(defn ->line-numbering-reader [r]
   (if (instance? LineNumberingPushbackReader r)
     r
     (LineNumberingPushbackReader. r)))

 (defn empty-reader? [^PushbackReader r]
   (let [char (.read r)]
     (if (= -1 char)
       true
       (do
         (.unread r char)
         false))))

 (defn reader->forms [r]
   (let [r (->line-numbering-reader r)]
     (->> #(LispReader/read r false ::eof false)
       repeatedly
       (take-while #(not= ::eof %)))))

;;;

 (defn newline-generator []
   (let [counter (atom 1)]
     (fn [current-line]
       (if-not current-line
         ""
         (let [diff (max 0 (- current-line @counter))]
           (swap! counter + diff)
           (->> "\n" (repeat diff) (apply str)))))))

 (defn line-preserving-pr-str [newlines & vals]
   (binding [*newlines* newlines
             *print-dup* true]
     (apply pr-str vals)))

;;;

 (defn lazy-reader-seq [s]
   (let [s (atom s)]
     (proxy [Reader] []
       (close []
         )
       (read [cbuf offset len]
         (if-let [^Reader r (first @s)]
           (let [c (.read r)]
             (if (= -1 c)
               (do
                 (swap! s rest)
                 (.read this cbuf offset len))
               (do
                 (aset cbuf offset (char c))
                 1)))
           -1)))))

 (defn dechunk [s]
   (when-not (empty? s)
     (cons
       (first s)
       (lazy-seq
         (dechunk (rest s))))))

;;;

 (defn transform-reader [transform r]
   (let [_ @switched-printer ;; prime the switched printer
         newlines (newline-generator)]
     (->> r
       reader->forms
       dechunk
       (map transform)
       (map #(line-preserving-pr-str newlines %))
       (map #(StringReader. %))
       lazy-reader-seq
       LineNumberingPushbackReader.)))

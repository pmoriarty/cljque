(ns cljque.combinators
  (:use cljque.api))

;;(defn latch-events [obs]
  ;; when all observables have sent an event
  ;; send an event containing all those events
;;)

;;; Reusable Observable implementations

(defn seq-observable
  "Returns a sequence of events from an observable.
  Consuming the sequence will block if no more events have been
  generated."
  [o]
  (let [terminator (Object.)
	q (java.util.concurrent.LinkedBlockingQueue.)
	consumer (fn this []
		   (lazy-seq
		    (let [x (.take q)]
		      (when (not= x terminator)
			(cons x (this))))))]
    (subscribe o (reify Observer
			(event [this observed event]
			       (.put q event))
			(done [this observed]
			      (.put q terminator))
			(error [this observed e]
			       (throw e))))
    (consumer)))

(defn observe-seq
  "Returns an observer that generates events by consuming a sequence
  on a separate thread."
  [s]
  (reify Observable
	 (subscribe [this observer]
		    (let [continue (atom true)]
		      (future (loop [xs s]
				(when @continue
				  (let [x (first xs)]
				    (if x
				      (do (event observer this x)
					  (recur (next xs)))
				      (done observer this))))))
		      (fn [] (reset! continue false))))))

(defn range-events
  ([]
     (reify Observable
	    (subscribe [this observer]
		       (let [continue (atom true)]
			 (future (loop [i 0]
				   (when @continue
				     (event observer this i)
				     (recur (inc i)))))
			 (fn [] (reset! continue false))))))
  ([finish]
     (range-events 0 finish))
  ([start finish]
     (reify Observable
	    (subscribe [this observer]
		       (let [continue (atom true)]
			 (future (loop [i start]
				   (when @continue
				     (if (< i finish)
				       (do (event observer this i)
					   (recur (inc i)))
				       (done observer this)))))
			 (fn [] (reset! continue true)))))))

(defn once
  "Returns an Observable which, when subscribed, generates one event
  with value then immediately signals 'done'."
  [value]
  (reify Observable
         (subscribe [this observer]
		    (future (event observer this value)
			    (done observer this))
		    (constantly nil))))

(defn never
  "Returns an Observable which, when subscribed, signals 'done'
  immediately."
  []
  (reify Observable
	 (subscribe [this observer]
		    (done observer this)
		    (constantly nil))))

;;; Wrappers

(defn handle-events
  "Returns an Observable which wraps the events generated by
  Observable o.  When an Observer subscribes to the returned
  Observable, f will be invoked instead of that observer's 'event'
  method.  'done' and 'error' signals are passed through to the
  Observer unchanged."
  [f o]
  (reify Observable
	 (subscribe [this observer]
		    (subscribe o (reify Observer
					(event [this observable value]
					       (f observer observable value))
					(done [this observable]
					      (done observer observable))
					(error [this observable e]
					       (error observer observable e)))))))

(defn take-events
  "Returns an Observable which wraps Observable o and passes up to n
  events to each subscriber."
  [n o]
  (let [key (Object.)
	sub-counts (ref {})]
    (reify Observable
	   (subscribe [this observer]
		      (dosync (alter sub-counts assoc key 0))
		      (subscribe o
				 (reify Observer
					(event [this observable value]
					       (let [c (dosync
							(when (contains? @sub-counts key)
							  (alter sub-counts update-in [key] inc)
							  (get @sub-counts key)))]
						 (cond (= c n)  (do (event observer observable value)
								    (done observer observable)
								    (dosync (alter sub-counts dissoc key)))
						       (< c n)  (event observer observable value))))
					(done [this observable]
					      (when (dosync
						     (when (contains? @sub-counts key)
						       (alter sub-counts update-in [key] inc)))
						(done observer observable)))
					(error [this observable e]
					       (error observer observable e))))
		      (fn [] (dosync (alter sub-counts dissoc key)))))))

(defn map-events
  "Returns an Observable which wraps Observable o by applying f to the
  value of each event."
  [f o]
  (handle-events (fn [observer observable value]
		   (event observer observable (f value)))
		 o))

(defn filter-events
  "Returns an Observable which wraps Observable o by only passing
  through events for which pred is true."
  [pred o]
  (handle-events (fn [observer observable value]
		   (when (pred value)
		     (event observer observable value)))
		 o))

(defn watch-events
  "Returns an Observable which generates events with
  [previous-value new-value] pairs for each event of Observable o.
  The first previous-value is the ns-qualified keyword ::unset"
  [o]
  (let [values (atom [nil ::unset])]
    (handle-events (fn [observer observable value]
		     (event observer observable
			    (swap! values (fn [[older old]] [old value]))))
		   o)))

(defn change-events
  "Returns an Observable which wraps Observable o and only generates
  events when the value changes."
  [o]
  (let [o (watch-events o)]
    (handle-events (fn [observer observable [old new]]
		     (when-not (= old new)
		       (event observer observable new)))
		   o)))

(defn delta-events
  "Returns an Observable which wraps Observable o. After the first
  event, applies f to the previous and current value of o and
  generates an event with f's return value."
  [f o]
  (let [o (watch-events o)]
    (handle-events (fn [observer observable [old new]]
		     (when-not (= old ::unset)
		       (event observer observable (f new old))))
		   o)))

(defn distinct-events
  "Returns an Observable which wraps Observable o and only generates
  events whose value has never been seen before."
  [o]
  (let [seen (ref #{})]
    (handle-events (fn [observer observable value]
		     (when-not (dosync
				(let [old-seen @seen]
				  (commute seen conj value)
				  (contains? old-seen value)))
		       (event observer observable value)))
		   o)))

(defn forward [source & targets]
  ;; How do you unsubscribe this?
  (subscribe source
	     (reify Observer
		    (event [this observed event]
			   (doseq [t targets]
			     (send! t event)))
		    (done [this observed] nil)
		    (error [this observed e]
			   (throw e)))))

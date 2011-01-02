(ns cljque.api)

;;; Message receiver protocols

(defprotocol Observer
  (event [observer observable event]
    "Called when observable generates an event.")
  (done [observer observable]
    "Called when observable is finished generating events.")
  (error [observer observable e]
    "Called when observable throws an exception e."))

(defprotocol Observable
  (subscribe [observable observer]
    "Subscribes observer to events generated by observable.  Returns a
    no-arg function which unsubscribes the observer."))

;;; Message sending protocols

(defprotocol MessageTarget
  (send! [this message]
    "Sends a message asynchronously."))

;;; IRefs are Observable

(extend clojure.lang.IRef
  Observable
  {:subscribe (fn [this-ref observer]
		(let [key (Object.)]
		  (add-watch this-ref key
			     (fn [key iref old new]
			       (event observer iref new)))
		  (fn [] (remove-watch this-ref key))))})

;;; Futures are Observable, but waiting for a result will occupy a
;;; thread in addition to the Future's thread

(defn- observe-future [fut observer]
  (try (event observer fut (.get fut))
       (catch Throwable t
	 (error observer fut t))
       (finally 
	(done observer fut))))

(extend-protocol Observable
  java.util.concurrent.Future
  (subscribe [this observer]
	     (if (.isDone this)
	       ;; If already done, generate events immediately
	       (do (observe-future this)
		   (constantly nil))
	       ;; If not done, wait for completion in another Future
	       (let [subscribed? (atom true)]
		 (future (let [value (.get this)]
			   (when subscribed?
			     (observe-future this observer))))
		 (fn [] (reset! subscribed? false))))))

;;; An Agent can wrap a MessageTarget and forward to it

(extend clojure.lang.Agent
  MessageTarget
  {:send! (fn [this-agent message]
	    (send this-agent send! message))})

;;; Functions are Observers

(extend clojure.lang.IFn
  Observer
  {:event (fn [this-fn observable event]
	    (this-fn observable event))
   :done (fn [this-fn observable]
	   (this-fn observable ::done))
   :error (fn [this-fn observable e]
	    (this-fn observable {::error e}))})

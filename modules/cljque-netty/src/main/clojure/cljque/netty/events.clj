(ns cljque.netty.events
  (:use cljque.api
        cljque.netty.util))

(import-netty)

;;; Observables and Netty channel events

(defn observer-channel-handler [observer]
  (channel-upstream-handler
   (fn [context channel-event]
     (if (instance? ExceptionEvent channel-event)
       (error observer context (.getCause channel-event))
       (event observer context channel-event))
     (.sendUpstream context channel-event))))

(defn messages [observable-channel]
  (map-events #(.getMessage %)
	      (filter-events #(instance? MessageEvent %)
			     observable-channel)))

;;; Message targets

(extend-protocol MessageTarget
  Channel
  (send! [this message] (Channels/write this message)))

;;; Observable channels & futures

(extend-protocol Observable
  Channel
  (subscribe [channel observer]
	     (let [key (name (gensym "observer-channel-handler"))
		   handler (observer-channel-handler observer)
		   pipeline (.getPipeline channel)
		   listener (channel-future-listener (fn [_] (done observer channel)))]
	       (.addLast pipeline key handler)
	       (.addListener (.getCloseFuture channel) listener)
	       (fn [] (.remove pipeline key)
		 (.removeListener listener))))
  ChannelFuture
  (subscribe [channel-future observer]
             (let [listener (channel-future-listener #(done observer %))]
               (.addListener channel-future listener)
               (fn [] (.removeListener channel-future listener)))))

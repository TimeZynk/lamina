;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns lamina.core.named
  (:use
    [lamina.core.channel :only (channel*)])
  (:require
    [lamina.cache :as cache])
  (:import
    [java.util.concurrent
     ConcurrentHashMap]))

(def named-channels (cache/channel-cache #(channel* :description (pr-str %) :permanent? true)))

(defn named-channel
  "something goes here"
  [id on-create]
  (cache/get-or-create named-channels id on-create))

(defn release-named-channel
  "something goes here"
  [id]
  (cache/release named-channels id))

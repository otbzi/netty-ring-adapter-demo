(ns board-fly.ring.adapter.netty
  (:use board-fly.ring.adapter.plumbing)
  (:require [clojure.tools.macro :as macro])
  (:import board_fly.ring.adapter.netty.IListenableFuture
           java.net.InetSocketAddress
           java.util.concurrent.Executors
           io.netty.channel.group.DefaultChannelGroup
           ring.adapter.netty.PrefixTF
           io.netty.bootstrap.ServerBootstrap
           io.netty.channel.socket.nio.NioServerSocketChannel
           io.netty.channel.socket.SocketChannel
           ;;[io.netty.util ThreadRenamingRunnable ThreadNameDeterminer]
           ;;io.netty.channel.socket.nio.NioServerSocketChannelFactory
           io.netty.channel.nio.NioEventLoopGroup
           io.netty.handler.stream.ChunkedWriteHandler
           [io.netty.channel ChannelPipeline ChannelInboundHandlerAdapter ChannelHandlerContext ChannelInitializer
            ;MessageEvent Channels ExceptionEvent ChannelEvent ChannelPipelineFactory SimpleChannelUpstreamHandler
            ]
           [io.netty.handler.codec.http HttpRequestDecoder HttpResponseEncoder 
            HttpObjectAggregator]))

(def default-server-options
  {"child.reuseAddress" true,
   "reuseAddress" true,
   "child.keepAlive" true,
   "child.connectTimeoutMillis" 4000,
   "tcpNoDelay" true,
   "child.tcpNoDelay" true})

;(defn- make-handler
;  [^DefaultChannelGroup channel-group handler]
;  (proxy [SimpleChannelUpstreamHandler] []
;    (channelOpen [ctx ^ChannelEvent e]
;      (.add channel-group (.getChannel e)))
;    (messageReceived [ctx ^MessageEvent e]
;      (let [request-map (build-request-map ctx (.getMessage e))
;            ring-response (or (handler request-map) {:status 404 :headers {}})]
;        (write-response ctx (request-map :keep-alive) ring-response)))
;    (exceptionCaught [ctx ^ExceptionEvent e]
;      ;; close it
;      (-> e .getChannel .close))))

;(defn- pipeline-factory
;  [^DefaultChannelGroup channel-group handler options]
;  (reify ChannelPipelineFactory
;    (getPipeline [this]
;      (doto (Channels/pipeline)
;        ;; maxInitialLine: 4k, maxHeaderSize: 4k, maxChunkSize: 8M
;        (.addLast "decoder" (HttpRequestDecoder. 4096 4096 8388608))
;        (.addLast "chunkagg" (HttpChunkAggregator. 8388608))
;        (.addLast "encoder" (HttpResponseEncoder.))
;        (.addLast "chunkedWriter" (ChunkedWriteHandler.))
;        (.addLast "handler" (make-handler channel-group handler))))))
;
(defmacro async-response
  "Wraps body so that a standard Ring response will be returned to caller when
  `(callback-name ring-response)` is executed in any thread:

     (defn my-async-handler! [request]
       (async-response respond!
         (future (respond! {:status  200
                            :headers {\"Content-Type\" \"text/html\"}
                            :body    \"This is an async response!\"}))))

  The caller's request will block while waiting for a response (see
  Ajax long polling example as one common use case)."
  [callback-name & body]
  `(let [data# (atom {})
         ~callback-name (fn [response#]
                          (swap! data# assoc :response response#)
                          (when-let [listener# (:listener @data#)]
                            (.run ^Runnable listener#)))]
     (do ~@body)
     {:status  200
      :headers {}
      :body    (reify IListenableFuture
                 (addListener [this# listener#]
                   (if (:response @data#)
                     (.run ^Runnable listener#)
                     (swap! data# assoc :listener listener#)))
                 (get [this#] (:response @data#)))}))

(defmacro defasync
  "(defn name [request] (async-response callback-name body))"
  {:arglists '(name [request] callback-name & body)}
  [name & sigs]
  (let [[name [[request] callback-name & body]]
        (macro/name-with-attributes name sigs)]
    `(defn ~name [~request] (async-response ~callback-name ~@body))))

;(defn run-netty [handler options]
;  ;; TODO: 不知道这里要干啥
;  ;;(ThreadRenamingRunnable/setThreadNameDeterminer ThreadNameDeterminer/CURRENT)
;  (let [cf (NioServerSocketChannelFactory.
;            (Executors/newCachedThreadPool (PrefixTF. "Server Boss"))
;            (Executors/newCachedThreadPool (PrefixTF. "Server Worker"))
;            (or (:worker options) 4))
;        server (ServerBootstrap. cf)
;        channel-group (DefaultChannelGroup.)]
;    (doseq [[k v] (merge default-server-options (:netty options))]
;      (.setOption server k v))
;    (.setPipelineFactory server (pipeline-factory channel-group handler options))
;    (.add channel-group (.bind server (InetSocketAddress. (:port options))))
;    (fn stop-server []
;      (-> channel-group .close .awaitUninterruptibly)
;      (.releaseExternalResources server))))
;

;(defn- make-handler
;  [^DefaultChannelGroup channel-group handler]
;  (proxy [SimpleChannelUpstreamHandler] []
;    (channelOpen [ctx ^ChannelEvent e]
;      (.add channel-group (.getChannel e)))
;    (messageReceived [ctx ^MessageEvent e]
;      (let [request-map (build-request-map ctx (.getMessage e))
;            ring-response (or (handler request-map) {:status 404 :headers {}})]
;        (write-response ctx (request-map :keep-alive) ring-response)))
;    (exceptionCaught [ctx ^ExceptionEvent e]
;      ;; close it
;      (-> e .getChannel .close))))


(defn- make-netty-handler 
  [ring-handler]
  (proxy [ChannelInboundHandlerAdapter] []
    (channelRead
      [^ChannelHandlerContext ctx msg]
      (let [request-map (build-request-map ctx msg)
            ring-response (or (ring-handler request-map) {:status 404 :headers {}})]
        (write-response ctx (request-map :keep-alive) ring-response)))
    ;; TODO: 底层用annotation做的，我不知道这么干是否合适……
    (isSharable
      []
      true)))

(defn- make-channel-initializer
  [netty-handler]
  (proxy [ChannelInitializer] []
    (initChannel 
      [^SocketChannel ch]
      (let [pipeline (.pipeline ch)]
        (doto pipeline 
          (.addLast "decoder" (HttpRequestDecoder. 4096 4096 8388608))
          (.addLast "chunkagg" (HttpObjectAggregator. 8388608))
          (.addLast "encoder" (HttpResponseEncoder.))
          ;(.addLast "chunkedWriter" (ChunkedWriteHandler.))
          (.addLast "handler" netty-handler))))))

(defn run-netty 
  [handler options]
  (let [boss-group (NioEventLoopGroup.)
        worker-group (NioEventLoopGroup.)]
    (try
      (let [server (ServerBootstrap.)
            netty-handler (make-netty-handler handler)
            channel-initializer (make-channel-initializer netty-handler)]
        (-> server
            (.group boss-group worker-group)
            (.channel NioServerSocketChannel)
            (.childHandler channel-initializer)

            ;; TODO, options
            ;(.option ChannelOption.SO_BACKLOG, 128)
            ;(.childOption ChannelOption.SO_KEEPALIVE, true)
            )
        (let [port (:port options)
              cf (-> server
                     (.bind port)
                     (.sync))]
          (-> cf 
              (.channel)
              (.closeFuture)
              (.sync))))
      (finally
        (.shutdownGracefully worker-group)
        (.shutdownGracefully boss-group)))))

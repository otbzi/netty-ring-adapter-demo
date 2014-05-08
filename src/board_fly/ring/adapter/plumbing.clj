(ns board-fly.ring.adapter.plumbing
  (:import [io.netty.channel ChannelHandlerContext]
           board_fly.ring.adapter.netty.Util
           io.netty.handler.codec.http.HttpRequest
           java.lang.IllegalArgumentException
           java.net.InetSocketAddress
           board_fly.ring.adapter.netty.IListenableFuture
           [io.netty.buffer ByteBufInputStream]
           [io.netty.handler.codec.http HttpHeaders HttpVersion
            HttpResponseStatus DefaultHttpResponse]))

(defn- remote-address [^ChannelHandlerContext ctx]
  (let [^InetSocketAddress a (-> ctx .channel .remoteAddress)]
    (-> a .getAddress .getHostAddress)))

;; TODO: 可以优化
(defn- get-headers  [^HttpRequest req]
  (reduce (fn [headers ^String name]
            (assoc headers (.toLowerCase name) (HttpHeaders/getHeader req name)))
          {}
          (keys (.headers req))))

(defn- uri-query [^HttpRequest req]
  (let [uri ^String (.getUri req)
        idx (.indexOf uri "?")]
    (if (= idx -1) [uri nil]
        [(subs uri 0 idx) (subs uri (inc idx))])))

(defn- domain-port [^HttpRequest req]
  (let [host (HttpHeaders/getHost req)
        idx (.indexOf host ":")]
    (if (= idx -1) (list host 80)
        (list (subs host 0 idx) (Integer/parseInt
                                 (subs host (inc idx)))))))

(defn build-request-map
  "Converts a netty request into a ring request map, TODO http/1.0 use
   keep-alive too"
  [^ChannelHandlerContext ctx ^HttpRequest req]
  (let [headers (get-headers req)
        [domain port] (domain-port req)
        [uri query-string] (uri-query req)]
    {:server-port        port
     :server-name        domain
     :remote-addr        (remote-address ctx)
     :uri                uri
     :query-string       query-string
     :scheme             (keyword (headers "x-scheme" "http"))
     :request-method     (-> req .getMethod .name .toLowerCase keyword)
     :headers            headers
     :content-type       (headers "content-type") 
     ;; TODO: content-length 可能为空
     :content-length     (HttpHeaders/getContentLength req 0) 
     :character-encoding (headers "content-encoding")
     :body               (ByteBufInputStream. (.content req))
     :keep-alive         (HttpHeaders/isKeepAlive req)}))

(defn- set-headers [^DefaultHttpResponse resp headers keep-alive]
  (HttpHeaders/setHeader resp "Server" "Netty-ring")
  (HttpHeaders/setHeader resp "Date" (Util/getDate))
  (when keep-alive (HttpHeaders/setHeader resp "Connection" "Keep-Alive"))
  (doseq [[^String key val-or-vals]  headers]
    (if (string? val-or-vals)
      (HttpHeaders/setHeader resp key ^String val-or-vals)
      (doseq [val val-or-vals]
        (HttpHeaders/addHeader resp key val)))))

(defn write-response
  [^ChannelHandlerContext ctx keep-alive {:keys [status body] :as ring-resp}]
  (if (instance? IListenableFuture body)
    (.addListener ^IListenableFuture body
                  (fn [] (write-response ctx keep-alive
                                        (let [r (.get ^IListenableFuture body)]
                                          (if (map? r) r
                                              {:status 200 :headers {} :body r})))))
    (let [resp (DefaultHttpResponse. HttpVersion/HTTP_1_1
                 (HttpResponseStatus/valueOf status))]
      (set-headers resp (:headers ring-resp) keep-alive)
      (Util/writeResp ctx resp body keep-alive))))

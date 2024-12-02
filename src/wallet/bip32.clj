(ns wallet.bip32
  (:require [clojure.string :as str]
            [buddy.core.mac :as mac]
            [buddy.core.hash :as hash]
            [wallet.base58 :as b58]
            [wallet.networks :as net]
            [buddy.core.codecs :as codecs])
  (:import [org.bouncycastle.crypto.params ECPrivateKeyParameters ECDomainParameters]
           [org.bouncycastle.crypto.ec CustomNamedCurves]))
;;;
;;; Utils
;;;

(defn- unsigned->signed [val]
  (if (> val 127)
    (- val 256)
    val))

(defn- int->n-byte-array [i n]
  (let [bytes (byte-array n)]
    (loop [idx (dec n) v i]
      (if (< idx 0)
        bytes
        (do (->> 0xff
                 (bit-and v)
                 unsigned->signed
                 (aset-byte bytes idx))
            (recur (dec idx) (bit-shift-right v 8)))))))

;;;
;;; Key making and validating
;;;

(defn validate-private-key
  [^bytes key-bytes]
  (when-let [msg (cond (not= 32 (count key-bytes)) "The key byte size msut be 32"

                       (not (< 0 (BigInteger. 1 key-bytes)
                               (-> "secp256k1"
                                   CustomNamedCurves/getByName
                                   .getN)))
                       "The key is out of the EC range")]
    (throw (ex-info "Key validation failed, a new seed required" {:reason msg}))))

(defn private-key->public-key [private-key-bytes]
  ;; Return compressed pub key
  (-> "secp256k1"
      CustomNamedCurves/getByName
      .getG
      (.multiply (BigInteger. 1 private-key-bytes))
      (.getEncoded true)))

(defn seed->hd-key
  "Creates a root private key from 64-byte seed
   seed: byte array of exactly 64 bytes
   version: (optional) network version bytes, defaults to mainnet xprv"
  ([seed]
   (seed->hd-key seed (get-in net/+networks+ ["main" "xprv"])))
  ([seed version] ;; Generate HMAC-SHA512 of the seed
   (let [raw (mac/hash seed {:key "Bitcoin seed" :alg :hmac+sha512})
         ;; Split into private key and chain code
         private-bytes (byte-array (take 32 raw))
         chain-code (byte-array (drop 32 raw))]
     (validate-private-key private-bytes)
     (make-hd-private-key private-bytes chain-code version 0 0 0))))

;;;
;;; HD (Hierarchical Deterministic) Key from a mnemonic phrase and an optional password
;;;
(defonce +hardened-index+ 0x80000000)

(defn hardened-index? [i]
  (>= i +hardened-index+))

(defprotocol HDKey
  (make-child-data-bytes [parent index])
  (make-hd-key [parent raw-bytes])
  (get-fingerprint [this]))

(defn hash160 [x]
  (-> x hash/sha256 hash/ripemd160))

(defn parse-path [path]
  ;; "m/44h/1'/0'/0/32"
  (let [path-list (str/split path #"/")]
    (for [i (if (= (first path-list) "m") ; master key?
              (rest path-list)
              path-list)
          :let [hardened? (contains? #{\' \h \H} (last i))]]
      (if hardened?
        (-> (subs i 0 (dec (count i))) (Integer/parseInt) (+ +hardened-index+))
        (Integer/parseInt i)))))

(defrecord HDPrivateKey [key chain-code version fingerprint depth child-index]
  HDKey
  (make-child-data-bytes [parent index]
    ;; only hardened or not matters, not public/private
    (-> (if (hardened-index? index)
          `[0x0 ~@key ~@(int->n-byte-array index 4)]
          `[~@(private-key->public-key key) ~@(int->n-byte-array index 4)])
        (byte-array)))

  (make-hd-key [parent secret]
    (-> (.mod (.add (BigInteger. 1 key) (BigInteger. 1 secret))
              (-> "secp256k1" CustomNamedCurves/getByName .getN))
        (.toByteArray)))

  (get-fingerprint [this]
    (or fingerprint
        (->> {:key (private-key->public-key key)}
             make-hd-public-key
             get-fingerprint))))

(defn make-hd-private-key [key chain-code version fingerprint depth child-index]
  (->HDPrivateKey key chain-code version fingerprint depth child-index))

(defrecord HDPublicKey [key chain-code version fingerprint depth child-index]
  HDKey
  (make-child-data-bytes [this index]
    (when (hardened-index? index)
      (throw (ex-info "Can't derive a hardened key from a public key"
                      {:parent this :index index})))
    `[~@(private-key->public-key key) ~@(int->n-byte-array index 4)])

  (make-hd-key [parent raw-bytes]
    (-> "secp256k1"
        CustomNamedCurves/getByName
        .getCurve
        .getG
        (.multiply (BigInteger. 1 raw-bytes))
        (.add (BigInteger. 1 key))
        .toByteArray))

  (get-fingerprint [this]
    (or fingerprint
        (->> key hash160 (take 4) byte-array codecs/bytes->hex))))
(defn make-hd-public-key
  ([m]
   (map->HDPublicKey m))
  ([key chain-code version depth child-index]
   (->HDPublicKey key chain-code version fingerprint depth child-index)))

(defn key->version [key]
  (if (= (count key) 32)
    (get-in net/+networks+ ["main" "xprv"])
    (get-in net/+networks+ ["main" "xpub"])))

(defn make-child-key [key chain-code index {:keys [depth]}]
  (if (= (count key) 32)
    (make-hd-private-key key chain-code (get-in net/+networks+ ["main" "xprv"])
                         (inc depth) index)
    (make-hd-public-key key chain-code (get-in net/+networks+ ["main" "xpub"])
                        (inc depth) index)))

(defn derive-child [{:keys [key chain-code depth] :as parent} index]
  (when (> index 0xFFFFFFFF) ;; (hardened) index <= 2^32
    (throw (ex-info "Index must be: index <= 2^32" {:index index})))

  (let [raw (mac/hash (make-child-data-bytes parent index) {:key chain-code :alg :hmac+sha512})
        ;; Split into key and chain code
        raw-bytes (byte-array (take 32 raw))
        child-chain-code (byte-array (drop 32 raw))
        _ (assert (and (= (count raw-bytes) 32)
                       (= (count child-chain-code) 32)))
        child-key (make-hd-key parent raw-bytes)]
    (make-child-key child-key
                    child-chain-code
                    (key->version child-key)
                    (inc depth)
                    index)))

(defn path->child [k path]
  (loop [[idx & more] (if (string? path)
                        (parse-path path)
                        path)
         parent k]
    (if idx
      (recur more (derive-child parent idx))
      parent)))

(defn hd-key->master-private-key [{:keys [key chain-code version depth version fingerprint child-index]}]
  (let [raw-key-bytes (let [len (count key)]
                        (case len
                          33 key

                          (31 32)
                          (let [padded-bytes (byte-array 33)
                                n-bytes (- 33 len)]
                            (dotimes [i n-bytes]
                              (aset-byte padded-bytes i 0x00))
                            (->> key
                                 count
                                 (min 32)
                                 (System/arraycopy key 0 padded-bytes n-bytes))
                            padded-bytes)))]
    ;; Check with
    ;;https://learnmeabitcoin.com/technical/keys/hd-wallets/extended-keys/
    (b58/encode-check (byte-array `[~@(vec version) ; 4 bytes
                                    ~depth          ; 1 byte
                                    ~@(vec (int->n-byte-array fingerprint 4)) ; 4 bytes
                                    ~@(vec (int->n-byte-array child-index 4)) ; 4 bytes
                                    ~@(vec chain-code)                        ; 32 bytes
                                    ~@(vec raw-key-bytes)]))))

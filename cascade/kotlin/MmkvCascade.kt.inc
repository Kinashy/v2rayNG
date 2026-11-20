
    /** Upgrade only the old factory DNS tuple; keep custom choices and profile stores. */
    fun ensureCascadeDefaults() = synchronized(settingsStorage) {
        settingsStorage.lock()
        try {
            val keys = settingsStorage.allKeys().orEmpty().toSet()
            val types = CascadeDefaults.settings +
                (CascadeDefaults.DNS_MIGRATION_KEY to CascadeDefaults.DNS_MIGRATION_VERSION)
            val existing = types.filterKeys { it in keys }.mapValues { (key, value) ->
                when (value) {
                    is String -> settingsStorage.decodeString(key).orEmpty()
                    is Boolean -> settingsStorage.decodeBool(key)
                    is Int -> settingsStorage.decodeInt(key)
                    is Set<*> -> settingsStorage.decodeStringSet(key).orEmpty()
                    else -> error("Unsupported distribution setting type")
                }
            }
            CascadeDefaults.applySettings(
                existing,
                { key, value ->
                    when (value) {
                        is String -> settingsStorage.encode(key, value)
                        is Boolean -> settingsStorage.encode(key, value)
                        is Int -> settingsStorage.encode(key, value)
                        is Set<*> -> settingsStorage.encode(key, value.filterIsInstance<String>().toSet())
                        else -> error("Unsupported distribution setting type")
                    }
                },
                { settingsStorage.remove(it) }
            )
        } catch (error: Exception) {
            LogUtil.e(TAG, "Distribution settings migration failed", error)
            throw error
        } finally {
            settingsStorage.unlock()
        }
    }

    /** Asset names, rather than URL or GUID, determine whether a user already has an entry. */
    fun ensureCascadeAssets() = synchronized(assetStorage) {
        assetStorage.lock()
        try {
            val existingNames = decodeAssetUrls().map { it.assetUrl.remarks }
            val missing = CascadeDefaults.missingAssets(existingNames).map { (name, url) ->
                Utils.getUuid() to AssetUrlItem(remarks = name, url = url)
            }.toMap()
            CascadeDefaults.addMissing(
                assetStorage.allKeys().orEmpty().toSet(), missing,
                { key, value -> assetStorage.encode(key, JsonUtil.toJson(value)) },
                { assetStorage.remove(it) }
            )
        } finally {
            assetStorage.unlock()
        }
    }

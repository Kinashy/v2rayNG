
    /** Seed absent distribution settings; never overwrite user choices or profile stores. */
    fun ensureCascadeDefaults() = synchronized(settingsStorage) {
        settingsStorage.lock()
        try {
            CascadeDefaults.addMissing(
                settingsStorage.allKeys().orEmpty().toSet(), CascadeDefaults.settings,
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

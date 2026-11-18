package com.instadown.assist.resolver

import com.instadown.assist.resolver.model.ResolveResult

interface InstagramResolver {
    /**
     * Resolves Instagram media metadata from a given post/reel URL.
     *
     * @param url The input Instagram URL
     * @param validateCdn Whether to execute Stage B lightweight CDN accessibility verification
     */
    suspend fun resolve(url: String, validateCdn: Boolean = false): ResolveResult
}

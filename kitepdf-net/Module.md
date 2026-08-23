# Module kitepdf-net

Optional. Opens a document straight from a URL.

The engine artifacts depend on kotlin-stdlib and KiteImage only, and this one
exists so that stays true: it is the single place Ktor enters the build. Add it
only if you load documents over the network, and add a Ktor engine next to it
(CIO, OkHttp, Darwin, Js) exactly as you would for any Ktor library.

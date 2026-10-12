# Module kitepdf-net

Optional. Opens a document from a URL.

This is the only artifact that brings Ktor into the build, so the other artifacts stay free of network code. Add it only when you load documents over the network. Add a Ktor client engine next to it (such as CIO, OkHttp, Darwin or Js), as for any Ktor library.

See [Loading a document](https://yuroyami.github.io/KitePDF/loading/).

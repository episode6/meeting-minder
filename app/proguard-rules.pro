# App-specific R8 keep rules. Library rules ship as consumer rules with each
# dependency; only add entries here when a release build fails or misbehaves
# without them.

# Keep file/line info so release stack traces stay mappable via mapping.txt
-keepattributes SourceFile,LineNumberTable

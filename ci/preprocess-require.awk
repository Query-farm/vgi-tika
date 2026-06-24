# Copyright 2026 Query Farm LLC - https://query.farm
#
# Rewrite each `require <ext>` gate in this repo's sqllogictest files into an
# explicit signed INSTALL+LOAD, so the prebuilt standalone `haybarn-unittest`
# (which links none of these extensions) can run the suite. The vgi extension
# comes from the signed community channel; httpfs/json/parquet/spatial from the
# signed core channel. `require-env` and every other directive pass through
# untouched. See ci/README.md.
#
# Additionally, when invoked with `-v transport=http`, inject an explicit signed
# `INSTALL httpfs FROM core; LOAD httpfs;` immediately AFTER each `LOAD vgi;`
# line. The vgi extension's HTTP worker transport routes its RPC through DuckDB's
# httpfs HTTP client — an http:// ATTACH without httpfs throws "VGI HTTP
# transport requires the httpfs extension". httpfs is unused by the
# subprocess/unix transports, so we only inject it on the http leg.
/^require[ \t]+vgi[ \t]*$/ {
    print "statement ok"; print "INSTALL vgi FROM community;"; print "";
    print "statement ok"; print "LOAD vgi;";
    if (transport == "http") {
        print "";
        print "statement ok"; print "INSTALL httpfs FROM core;"; print "";
        print "statement ok"; print "LOAD httpfs;";
    }
    next
}
/^require[ \t]+(httpfs|json|parquet|spatial)[ \t]*$/ {
    ext = $2
    print "statement ok"; print "INSTALL " ext " FROM core;"; print "";
    print "statement ok"; print "LOAD " ext ";"; next
}
{ print }
/^LOAD[ \t]+vgi;[ \t]*$/ {
    if (transport == "http") {
        print "";
        print "statement ok"; print "INSTALL httpfs FROM core;"; print "";
        print "statement ok"; print "LOAD httpfs;";
    }
}

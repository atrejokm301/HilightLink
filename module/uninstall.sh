#!/system/bin/sh
# Stop the restart loop first, then the helper it keeps alive (its process name is hilightlinkd).
pkill -f "modules/hilightlink/service.sh"
pkill -f "^hilightlinkd"

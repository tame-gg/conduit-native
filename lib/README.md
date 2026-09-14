# Velocity API jars for compiling src/compat-velocity (not shipped as Conduit core).
#
# Populate with:
#   ./scripts/fetch-velocity-compat.ps1
#
# Required:
#   velocity-api (PaperMC snapshot)
#   slf4j-api + slf4j-nop
#   javax.inject
#   adventure-api / adventure-key / plain+gson+legacy serializers
#   examination-api / examination-string
#   guava + failureaccess
#   gson
#   brigadier
#
# Conduit core never depends on these jars at compile time. They are only on the
# classpath when building the Velocity compatibility layer and Phase9 tests.

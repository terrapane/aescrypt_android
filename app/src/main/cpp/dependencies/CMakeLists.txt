# Enable fetching content
include(FetchContent)

# Set the root of the Terrapane source repositories
if(DEFINED ENV{TERRAPANE_SOURCE_URI})
    set(TERRAPANE_SOURCE_URI "$ENV{TERRAPANE_SOURCE_URI}")
else()
    set(TERRAPANE_SOURCE_URI "https://github.com/terrapane")
endif()

# Fetch the AES Crypt Engine
FetchContent_Declare(aescrypt_engine
    GIT_REPOSITORY ${TERRAPANE_SOURCE_URI}/aescrypt_engine.git
    GIT_TAG        v4.1.6
    GIT_SHALLOW    true)

# Fetch the Logger library
FetchContent_Declare(logger
    GIT_REPOSITORY ${TERRAPANE_SOURCE_URI}/logger.git
    GIT_TAG        v1.1.0
    GIT_SHALLOW    true)

# Fetch the Security Utilities library
FetchContent_Declare(secutil
    GIT_REPOSITORY ${TERRAPANE_SOURCE_URI}/secutil.git
    GIT_TAG        v1.1.1
    GIT_SHALLOW    true)

# Make dependencies available
FetchContent_MakeAvailable(aescrypt_engine logger secutil)

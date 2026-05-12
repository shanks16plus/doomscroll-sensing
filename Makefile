ADB     := $(HOME)/Library/Android/sdk/platform-tools/adb
PACKAGE := nl.utwente.doomscroll
APK     := app/build/outputs/apk/debug/app-debug.apk
DATA_DIR := $(HOME)/Documents/thesis\ /data

# ── Phone ─────────────────────────────────────────────────────────────────────

launch:                          ## Open the app on the connected phone
	$(ADB) shell am start -n $(PACKAGE)/.MainActivity

# ── Build & Install ───────────────────────────────────────────────────────────

build:                           ## Compile debug APK
	./gradlew assembleDebug

install: build                   ## Build + install on phone (keeps permissions)
	$(ADB) install -r $(APK)

fresh: build                     ## Build + wipe old install + fresh install
	$(ADB) uninstall $(PACKAGE) || true
	$(ADB) install $(APK)

# ── Data ──────────────────────────────────────────────────────────────────────

pull:                            ## Pull exported data from phone → thesis/data/
	mkdir -p $(DATA_DIR)
	$(ADB) pull /sdcard/Download/psu_export/ $(DATA_DIR)/
	@echo "✓ Data saved to $(DATA_DIR)/psu_export"
	open $(DATA_DIR)

# ── Git ───────────────────────────────────────────────────────────────────────

push:                            ## Stage all changes, commit with message, push
	@read -p "Commit message: " msg; \
	git add -A && \
	git commit -m "$$msg" && \
	git push

# ── Help ──────────────────────────────────────────────────────────────────────

help:                            ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*##' Makefile | awk 'BEGIN {FS = ":.*##"}; {printf "  \033[36mmake %-10s\033[0m %s\n", $$1, $$2}'

.PHONY: launch build install fresh pull push help
.DEFAULT_GOAL := help

# Marks llm_sidecar.tests as a subpackage so pytest (prepend import mode) walks
# up to nlp-python/ — the first dir without __init__.py — and puts it on sys.path,
# making `import llm_sidecar.*` resolve during collection.

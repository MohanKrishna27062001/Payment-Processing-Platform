# The fake payment processor with this project's behaviour baked in, so it can be published and run
# without the stub files on disk:
#
#     docker build -t payments-wiremock backend/stubs
#
# Locally, compose still mounts ./backend/stubs over /home/wiremock instead, so editing a mapping needs no
# rebuild. This image is for running the published stack elsewhere.
FROM wiremock/wiremock:3.9.1

COPY mappings/ /home/wiremock/mappings/

# Response templating fills in the generated processor references (proc_flaky_<uuid>, ...).
CMD ["--global-response-templating"]

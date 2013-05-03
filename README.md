# lein-boot

A Leiningen plugin to run ring-servlet with Servlet 3 API.

## Usage

Put `[com.andrewmcveigh/lein-boot "0.1.1"]` into the `:plugins` vector
of your project.clj.

You'll need the following dependencies:

```clojure
[ring/ring-servlet "1.1.0" :exclusions [javax.servlet/servlet-api]]
```

You'll maybe also want to include ring/ring-devel. Probably best to put these
in your :dev profile:

```clojure
:profiles {:dev {:dependencies [[ring/ring-devel "1.1.0"]]}}
```

## Example

    $ lein boot
    $ lein boot :port 8080

## License

Copyright © 2013 Andrew Mcveigh

Distributed under the Eclipse Public License, the same as Clojure.

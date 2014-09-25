# lein-boot

A Leiningen plugin to run lein-ring and ring-servlet with Servlet 3 API.

## Usage

Put `[com.andrewmcveigh/lein-boot "0.2.2"]` into the `:plugins` vector
of your project.clj.

This plugin automatically adds the following dependencies, if other versions do
not already exist in your project.clj:

```clojure
[ring/ring-servlet "1.1.8"]
[org.eclipse.jetty/jetty-webapp "8.1.0.RC5"]
```

### Hooks

The following hooks exist

    leiningen.boot.hooks.test
    leiningen.boot.hooks.jar
    leiningen.boot.hooks.uberjar

To put in your project.clj

```clojure
:hooks [leiningen.boot.hooks.test
        leiningen.boot.hooks.jar
        leiningen.boot.hooks.uberjar]
```

## Example

    $ lein boot
    $ lein boot :port 8080
    $ lein test
    $ lein ring jar
    $ lein ring uberjar

## License

Copyright © 2013 Andrew Mcveigh

Distributed under the Eclipse Public License, the same as Clojure.

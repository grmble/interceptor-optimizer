# Interceptor Optimizer

Optimizes your interceptor chain by interceptor composition.

```clojure
{:enter ^:composable (fn [x] (print x) x)}
```

Composable interceptors are composed by the optimizer, thereby
reducing the overhead introduced by using interceptors.

Composable interceptors must not

* return effectful values (e.g promises, async channels, ..)
* modify the interceptor queue


## How does it work?

*Trigger Warning: m-word ahead*

With some squinting, interceptors can be seen as a monad.
The effects that are provided are

* some form of (asynchronous ?) execution
* error handling via `:error` interceptors
* throwing exceptions which will pass control to the next `:error` interceptor
* manipulation of the queue or anything else that changes the flow of control

But every monad is also a functor.  Again with a little bit
of squinting, every interceptor that just returns a plain
request or response map can be seen as a functor.

The nice bit about functors is that they compose via function composition.

https://cs.stackexchange.com/questions/129822/functor-in-category-theory-the-free-theorem-for-fmap


### An example

```clojure
 ((comp #(conj % "outer") #(conj % "inner")) [])
  ;; => ["inner" "outer"]

  (-> [] (conj "inner") (conj "outer"))
  ;; => ["inner" "outer"]
  

  @(-> (p/do [])
       (p/then #(conj % "inner"))
       (p/then #(conj % "outer")))
  ;; => ["inner" "outer"]

  @(-> (p/do [])
       (p/then (fn [x] ((comp #(conj % "outer")
                              #(conj % "inner")) x))))
  ;; => ["inner" "outer"]
```

E.g. with promesa, as long as your `p/then` handlers
just return plain effect-less values, you can can
compose them (but be careful about order. comp composes
right-to-left).  This is more efficient because
while `CompletableFuture` is relatively cheap, it does
have some overhead - in the background, a `Runnable` is
executed on some thread pool.

For completeness sake, effectful handlers could be
composed using Kleisli composition, but that is just what
the interceptors machinery is doing anyway - it does not
buy us anything.  The point here is that functors can
be composed using plain function composition, allowing us
to circumvent the ju3,6+3.c.3fre3625tinterceptor machinery.

### Implementation

In the absence of a type system enforced by the compiler,
we have to find another way to determine which interceptors
are composable.  This implementation uses the honor system -
a interceptor can tell us it's composable by providing
`:composable` metadata on the `:enter` or `:leave` functions.

The optimizer takes a seq of interceptors and separates out
`:enter`, `:leave` and `:error` phases (if you had one interceptor
with all three, now you have three interceptors with one
function each).  `:leave` and `:error` are collected in reverse order,
`:enter` normally.  Then all consecutive runs of `:comparable`
are reversed and composed.

Throwing errors actually is not a problem - this behaves
the same from separate interceptors vs a composed one.
Error interceptors are not a problem either - we only handle
`:enter` and `:leave`.  The handler is turned into an `:enter`
interceptor, just like regular 'reitit'.


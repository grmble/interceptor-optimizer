# Interceptor Optimizer

Proof of concept for optimizing reitit interceptors by
(ab)using function composition.

Interceptors have to opt-in to this optimization by
declaring that they are `:composable`:

```clojure
{:enter ^:composable (fn [x] (print x) x)}
```

Composable interceptors are composed by the optimizer, thereby
reducing the overhead introduced by using interceptors.

```clojure
(http/ring-handler
           (http/router ["/answer" (optimize {:interceptors interceptors
                                              :handler handler})])
           {:executor sieppari/executor})
```

Composable interceptors must not

* return effectful values (e.g promises, async channels, ..)
* modify the interceptor queue


## Speed comparison

Example interceptors: 1 execption handler, one `:enter` interceptor
increasing a counter in the context, one `:leave` interceptor decreasing
the counter.  Plus a handler that produces a string with said counter.

Unoptimized/plain reitit:

```
Evaluation count : 36067560 in 60 samples of 601126 calls.
             Execution time mean : 1,675149 µs
    Execution time std-deviation : 21,175112 ns
   Execution time lower quantile : 1,659077 µs ( 2,5%)
   Execution time upper quantile : 1,721012 µs (97,5%)
                   Overhead used : 7,087744 ns

Found 12 outliers in 60 samples (20,0000 %)
	low-severe	 6 (10,0000 %)
	low-mild	 6 (10,0000 %)
 Variance from outliers : 1,6389 % Variance is slightly inflated by outliers
```

Same example optimized (the exception handler is untouched, but the
3 other functions are composed together)

```
Evaluation count : 41838960 in 60 samples of 697316 calls.
             Execution time mean : 1,446811 µs
    Execution time std-deviation : 35,587961 ns
   Execution time lower quantile : 1,429353 µs ( 2,5%)
   Execution time upper quantile : 1,481236 µs (97,5%)
                   Overhead used : 7,087744 ns

Found 10 outliers in 60 samples (16,6667 %)
	low-severe	 9 (15,0000 %)
	low-mild	 1 (1,6667 %)
 Variance from outliers : 12,5682 % Variance is moderately inflated by outliers
```

A modest improvement, but to me the real benefit is that this also
helps with async code: every interceptor after the first async one
will have to be scheduled on a thread pool, if multiple interceptors can
be composed together this overhead can be significantly reduced.

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
to circumvent the interceptor machinery.

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


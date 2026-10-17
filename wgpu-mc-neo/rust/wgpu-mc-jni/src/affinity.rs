//! Which cores a thread is allowed to run on, on the phones this renderer ships to.
//!
//! A big.LITTLE phone is two machines sharing a battery: one cluster of performance cores running
//! roughly twice the clock and much more than twice the work per clock, and one cluster of
//! efficiency cores that exist to keep the idle drain down. The scheduler places threads by
//! utilization, and it does a reasonable job of it - but the two threads this renderer cares most
//! about are exactly the two its heuristics get wrong in a game's shape of workload:
//!
//! - **the render thread**, which submits every frame and pays a migration to a slow core in lost
//!   frame time, and which the scheduler will happily park on a little core for a while because a
//!   burst of bake work looked busier;
//! - **the section-bake pool**, whose threads are pure throughput - and whose throughput per core on
//!   a little core is a fraction of what the same work costs on a big one, which is why a world
//!   meshes in the time it does.
//!
//! Pinning is the honest answer to both: the render thread onto the performance cluster, and the
//! bake pool onto the same cluster with one thread per core - so the meshing stops competing with
//! itself for scheduler attention and the efficiency cluster is left to the JVM's own threads (the
//! garbage collector, audio, the launcher), which is what those cores are for.
//!
//! **The clusters are read, not assumed.** A core layout is a property of a SoC, and the same
//! release ships onto SM8150 (four big, four little), the 8-Elite generation (two primes, six
//! others, all fast) and anything between. `/sys/devices/system/cpu/cpuN/cpufreq/cpuinfo_max_freq`
//! says what each core can do, and the performance cluster is "the cores within reach of the
//! fastest" - a fraction that puts a 2.8 GHz core on SM8150 and a 3.5 GHz one on the 8-Elite into
//! the same set as the prime above them, and leaves a 1.8 GHz efficiency core out. A sysfs that
//! does not answer, or answers with nonsense, is answered with `None`: the caller keeps the
//! behaviour it has always had, which is the scheduler's own placement.
//!
//! Everything here is Android-only. A desktop build has no clusters to tell apart, and `libc`'s
//! affinity calls are behind the same `cfg` as the rest of it, so nothing on another OS pays for
//! or links what it cannot use.

#[cfg(target_os = "android")]
use std::sync::OnceLock;

/// The performance cores, most-capable first, or `None` when they cannot be told apart.
///
/// Read once and remembered: the answer is a property of the silicon, and the read is a walk of
/// sysfs - not a thing a per-frame caller should repeat.
#[cfg(target_os = "android")]
pub fn big_cores() -> Option<&'static [usize]> {
    static BIG_CORES: OnceLock<Option<Vec<usize>>> = OnceLock::new();

    BIG_CORES.get_or_init(detect_big_cores).as_deref()
}

/// Reads every core's maximum frequency out of cpufreq and keeps the fast ones.
///
/// The kernel names the file in kHz. The cores kept are the ones within [`BIG_RATIO`] of the
/// fastest, which is a cluster boundary on every layout this release can land on: SM8150's gap is
/// 2.8 GHz against 1.8 (a ratio of 0.62), so the little cluster is out, and a generation whose
/// second cluster runs at 0.8 of the prime keeps both - pinning to all of a homogeneous set being
/// the same as pinning to nothing.
#[cfg(target_os = "android")]
fn detect_big_cores() -> Option<Vec<usize>> {
    // How many cores there are, from the kernel's own map rather than from a count that stops at
    // the first missing file: `0-3,4-7` is the shape on the phones, but `present` is what says so.
    let present = std::fs::read_to_string("/sys/devices/system/cpu/present").ok()?;
    let present = present.trim();

    // "0-7", "0-3,4-7", "0" - every shape the kernel writes, expanded the one way that is correct
    // for all of them: each comma part is either a single number or a range, and the cores are the
    // union.
    let mut cores = Vec::new();

    for part in present.split(',') {
        let (start, end) = match part.split_once('-') {
            Some((start, end)) => (start, end),
            None => (part, part),
        };

        let start: usize = start.trim().parse().ok()?;
        let end: usize = end.trim().parse().ok()?;

        if end < start || end - start > 1024 {
            return None;
        }

        cores.extend(start..=end);
    }

    if cores.is_empty() {
        return None;
    }

    let freq_of = |core: usize| -> Option<u32> {
        std::fs::read_to_string(format!(
            "/sys/devices/system/cpu/cpu{core}/cpufreq/cpuinfo_max_freq"
        ))
        .ok()?
        .trim()
        .parse()
        .ok()
    };

    let fastest = cores.iter().filter_map(|core| freq_of(*core)).max()?;

    // A core with no cpufreq entry (hotplugged out, or a kernel that does not expose it) is not a
    // core this can classify, and a set where none classify is a set this cannot use. The ratio is
    // integer arithmetic - `freq×10 >= fastest×7` - because a float compare on the two ends of a
    // cluster boundary has burned people before.
    let big: Vec<usize> = cores
        .into_iter()
        .filter(|core| {
            freq_of(*core).is_some_and(|freq| freq.saturating_mul(10) >= fastest.saturating_mul(7))
        })
        .collect();

    if big.is_empty() { None } else { Some(big) }
}

/// How many bake threads to run, and where.
///
/// Answers the pool's sizing: one thread per performance core when the cores are known, and the
/// platform default when they are not - which is the behaviour this renderer has always had, one
/// thread per core whatever the cores are.
#[cfg(target_os = "android")]
pub fn bake_pool_threads() -> Option<usize> {
    Some(big_cores()?.len())
}

/// The rayon spawn handler that puts each bake thread on the performance cluster.
///
/// `None` when there is no cluster to name, in which case the pool is built exactly as it was
/// before this module existed. The handler is called on the *spawning* thread, so the pin has to
/// happen inside the thread it is sizing - which is what the closure here spawns to do.
#[cfg(target_os = "android")]
pub fn bake_pool_spawn_handler()
-> Option<impl FnMut(rayon::ThreadBuilder) -> std::io::Result<()> + Send + Sync + 'static> {
    let big_cores = std::sync::Arc::new(big_cores()?.to_vec());

    Some(
        move |builder: rayon::ThreadBuilder| -> std::io::Result<()> {
            // The set is cloned per spawn rather than moved, because the handler is `FnMut`: a
            // closure that hands its environment over the first time it is called is `FnOnce`, and
            // rayon's builder asks for one it can call for every thread of the pool.
            let cores = std::sync::Arc::clone(&big_cores);

            let name = builder.name().map(str::to_owned);

            let mut spawn = std::thread::Builder::new();
            if let Some(name) = &name {
                spawn = spawn.name(name.clone());
            }

            spawn.spawn(move || {
                pin_to(&cores);
                builder.run();
            })?;

            Ok(())
        },
    )
}

/// Pins the calling thread to the performance cluster, once per thread.
///
/// Called from the frame path, so the common case is one atomic load and one thread-id compare:
/// the pin survives for the life of the thread, and a JVM that hands the renderer a *different*
/// thread later gets that one pinned instead. A thread this cannot pin - no cluster, a syscall
/// that says no - is left where the scheduler put it, which is where it was before this call
/// existed either way.
#[cfg(target_os = "android")]
pub fn pin_render_thread() {
    use std::sync::atomic::{AtomicI32, Ordering};

    static PINNED_TID: AtomicI32 = AtomicI32::new(0);

    let tid = unsafe { libc::gettid() };

    if PINNED_TID.load(Ordering::Relaxed) == tid {
        return;
    }

    if let Some(cores) = big_cores() {
        pin_to(cores);
    }

    PINNED_TID.store(tid, Ordering::Relaxed);
}

/// Pins the calling thread to `cores`, and says nothing on the way out.
///
/// A pinned thread that outlives its usefulness is worse than an unpinned one, so the only caller
/// of this that runs per frame is the idempotent [`pin_render_thread`]; the pool pins once, at
/// thread start. `sched_setaffinity` failing (a core list the kernel does not accept, a seccomp
/// filter that hides the call) is not an error this side can act on.
///
/// The set is libc's own `cpu_set_t`, built with the helpers libc answers for on Android, and
/// sized by the ABI it compiles into: sixteen words on arm64, one on arm - which is why the core
/// list is truncated at what the type holds rather than at a constant. A mask handed over past
/// the ABI's size is a refusal, and a bit set past the type's storage is memory corruption; both
/// are worse than an unpinned thread, which is what a truncated list degrades to.
#[cfg(target_os = "android")]
fn pin_to(cores: &[usize]) {
    if cores.is_empty() {
        return;
    }

    let word_bits = std::mem::size_of::<libc::c_ulong>() * 8;
    let words = std::mem::size_of::<libc::cpu_set_t>() / word_bits;
    let max_cores = words * word_bits;

    let mut set: libc::cpu_set_t = unsafe { std::mem::zeroed() };

    unsafe {
        libc::CPU_ZERO(&mut set);

        for core in cores.iter().copied().take(max_cores) {
            libc::CPU_SET(core, &mut set);
        }

        let _ = libc::sched_setaffinity(0, std::mem::size_of::<libc::cpu_set_t>(), &set);
    }
}

/// Everything above, answered with "no" on an OS without the problem.
///
/// The callers are written against the `Option`-shaped answers so that this half is the same code
/// shape as the Android half: the pool builder takes `Option`s it forwards, and the frame-path pin
/// is a call that does nothing.
#[cfg(not(target_os = "android"))]
pub fn bake_pool_threads() -> Option<usize> {
    None
}

#[cfg(not(target_os = "android"))]
pub fn bake_pool_spawn_handler()
-> Option<impl FnMut(rayon::ThreadBuilder) -> std::io::Result<()> + Send + Sync + 'static> {
    None::<fn(rayon::ThreadBuilder) -> std::io::Result<()>>
}

#[cfg(not(target_os = "android"))]
pub fn pin_render_thread() {}

#[cfg(test)]
mod tests {
    /// The one thing a caller can rely on without a phone: the answers are `Option`s that say "no"
    /// on a desktop, and the pool builder treats that as "build it the way it was".
    #[test]
    fn a_desktop_answers_none_and_pins_nothing() {
        assert!(super::bake_pool_threads().is_none());
        assert!(super::bake_pool_spawn_handler().is_none());
        super::pin_render_thread();
    }
}
